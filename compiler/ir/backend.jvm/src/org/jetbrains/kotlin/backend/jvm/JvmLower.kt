/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.common.CommonBackendContext
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.lower.*
import org.jetbrains.kotlin.backend.common.lower.loops.forLoopsPhase
import org.jetbrains.kotlin.backend.common.lower.optimizations.foldConstantLoweringPhase
import org.jetbrains.kotlin.backend.common.phaser.*
import org.jetbrains.kotlin.backend.jvm.lower.*
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.util.PatchDeclarationParentsVisitor
import org.jetbrains.kotlin.ir.util.isAnonymousObject
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.load.java.JavaVisibilities
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.NameUtils

private val validateIrBeforeLowering = makeCustomPhase<JvmBackendContext, IrModuleFragment>(
    { context, module -> validationCallback(context, module) },
    name = "ValidateIrBeforeLowering",
    description = "Validate IR before lowering"
)

private val validateIrAfterLowering = makeCustomPhase<JvmBackendContext, IrModuleFragment>(
    { context, module -> validationCallback(context, module) },
    name = "ValidateIrAfterLowering",
    description = "Validate IR after lowering"
)

private val stripTypeAliasDeclarationsPhase = makeIrFilePhase<CommonBackendContext>(
    { StripTypeAliasDeclarationsLowering() },
    name = "StripTypeAliasDeclarations",
    description = "Strip typealias declarations"
)

// TODO make all lambda-related stuff work with IrFunctionExpression and drop this phase
private val provisionalFunctionExpressionPhase = makeIrFilePhase<CommonBackendContext>(
    { ProvisionalFunctionExpressionLowering() },
    name = "FunctionExpression",
    description = "Transform IrFunctionExpression to a local function reference"
)

private val arrayConstructorPhase = makeIrFilePhase(
    ::ArrayConstructorLowering,
    name = "ArrayConstructor",
    description = "Transform `Array(size) { index -> value }` into a loop"
)

private val expectDeclarationsRemovingPhase = makeIrModulePhase<JvmBackendContext>(
    { context -> ExpectDeclarationsRemoveLowering(context, keepOptionalAnnotations = true) },
    name = "ExpectDeclarationsRemoving",
    description = "Remove expect declaration from module fragment"
)

private val lateinitPhase = makeIrFilePhase(
    ::LateinitLowering,
    name = "Lateinit",
    description = "Insert checks for lateinit field references"
)

private val propertiesPhase = makeIrFilePhase<JvmBackendContext>(
    { context ->
        PropertiesLowering(context, JvmLoweredDeclarationOrigin.SYNTHETIC_METHOD_FOR_PROPERTY_ANNOTATIONS) { property ->
            val baseName =
                if (context.state.languageVersionSettings.supportsFeature(LanguageFeature.UseGetterNameForPropertyAnnotationsMethodOnJvm)) {
                    property.getter?.let { getter ->
                        context.methodSignatureMapper.mapFunctionName(getter)
                    } ?: JvmAbi.getterName(property.name.asString())
                } else {
                    property.name.asString()
                }
            JvmAbi.getSyntheticMethodNameForAnnotatedProperty(baseName)
        }
    },
    name = "Properties",
    description = "Move fields and accessors for properties to their classes",
    stickyPostconditions = setOf((PropertiesLowering)::checkNoProperties)
)

internal val localDeclarationsPhase = makeIrFilePhase<CommonBackendContext>(
    { context ->
        LocalDeclarationsLowering(
            context,
            object : LocalNameProvider {
                override fun localName(declaration: IrDeclarationWithName): String =
                    NameUtils.sanitizeAsJavaIdentifier(super.localName(declaration))
            },
            object : VisibilityPolicy {
                override fun forClass(declaration: IrClass, inInlineFunctionScope: Boolean): Visibility =
                    if (declaration.origin == JvmLoweredDeclarationOrigin.LAMBDA_IMPL ||
                        declaration.origin == JvmLoweredDeclarationOrigin.FUNCTION_REFERENCE_IMPL ||
                        declaration.origin == JvmLoweredDeclarationOrigin.GENERATED_PROPERTY_REFERENCE) {
                        scopedVisibility(inInlineFunctionScope)
                    } else {
                        declaration.visibility
                    }

                override fun forConstructor(declaration: IrConstructor, inInlineFunctionScope: Boolean): Visibility =
                    if (declaration.parentAsClass.isAnonymousObject)
                        scopedVisibility(inInlineFunctionScope)
                    else
                        declaration.visibility

                private fun scopedVisibility(inInlineFunctionScope: Boolean): Visibility =
                    if (inInlineFunctionScope) Visibilities.PUBLIC else JavaVisibilities.PACKAGE_VISIBILITY
            }
        )
    },
    name = "JvmLocalDeclarations",
    description = "Move local declarations to classes",
    prerequisite = setOf(callableReferencePhase, sharedVariablesPhase)
)

private val jvmLocalClassExtractionPhase = makeIrFilePhase(
    ::JvmLocalClassPopupLowering,
    name = "JvmLocalClassExtraction",
    description = "Move local classes from field initializers and anonymous init blocks into the containing class"
)

private val computeStringTrimPhase = makeIrFilePhase<JvmBackendContext>(
    { context ->
        if (context.state.canReplaceStdlibRuntimeApiBehavior)
            StringTrimLowering(context)
        else
            FileLoweringPass.Empty
    },
    name = "StringTrimLowering",
    description = "Compute trimIndent and trimMargin operations on constant strings"
)

private val defaultArgumentStubPhase = makeIrFilePhase(
    ::JvmDefaultArgumentStubGenerator,
    name = "DefaultArgumentsStubGenerator",
    description = "Generate synthetic stubs for functions with default parameter values",
    prerequisite = setOf(localDeclarationsPhase)
)

private val defaultArgumentInjectorPhase = makeIrFilePhase(
    ::JvmDefaultParameterInjector,
    name = "DefaultParameterInjector",
    description = "Transform calls with default arguments into calls to stubs",
    prerequisite = setOf(defaultArgumentStubPhase, callableReferencePhase, inlineCallableReferenceToLambdaPhase)
)

private val interfacePhase = makeIrFilePhase(
    ::InterfaceLowering,
    name = "Interface",
    description = "Move default implementations of interface members to DefaultImpls class",
    prerequisite = setOf(defaultArgumentInjectorPhase)
)

private val innerClassesPhase = makeIrFilePhase(
    ::InnerClassesLowering,
    name = "InnerClasses",
    description = "Add 'outer this' fields to inner classes",
    prerequisite = setOf(localDeclarationsPhase)
)

private val staticInitializersPhase = makeIrFilePhase(
    ::StaticInitializersLowering,
    name = "StaticInitializers",
    description = "Move code from object init blocks and static field initializers to a new <clinit> function"
)

private val initializersPhase = makeIrFilePhase<JvmBackendContext>(
    { context ->
        object : InitializersLowering(context) {
            override fun shouldEraseFieldInitializer(irField: IrField): Boolean =
                irField.constantValue(context) == null
        }
    },
    name = "Initializers",
    description = "Merge init blocks and field initializers into constructors",
    stickyPostconditions = setOf(fun(irFile: IrFile) {
        irFile.acceptVoid(object : IrElementVisitorVoid {
            override fun visitElement(element: IrElement) {
                element.acceptChildrenVoid(this)
            }

            override fun visitAnonymousInitializer(declaration: IrAnonymousInitializer) {
                error("No anonymous initializers should remain at this stage")
            }
        })
    }),
    // Depends on local class extraction, because otherwise local classes in initializers will be copied into each constructor.
    prerequisite = setOf(jvmLocalClassExtractionPhase)
)

private val returnableBlocksPhase = makeIrFilePhase(
    ::ReturnableBlockLowering,
    name = "ReturnableBlock",
    description = "Replace returnable blocks with do-while(false) loops",
    prerequisite = setOf(arrayConstructorPhase, assertionPhase)
)

private val syntheticAccessorPhase = makeIrFilePhase(
    ::SyntheticAccessorLowering,
    name = "SyntheticAccessor",
    description = "Introduce synthetic accessors",
    prerequisite = setOf(objectClassPhase, staticDefaultFunctionPhase, interfacePhase)
)

private val tailrecPhase = makeIrFilePhase<JvmBackendContext>(
    ::JvmTailrecLowering,
    name = "Tailrec",
    description = "Handle tailrec calls"
)

@Suppress("Reformat")
private val jvmFilePhases =
        renameAnonymousParametersLowering then
        typeAliasAnnotationMethodsPhase then
        stripTypeAliasDeclarationsPhase then
        provisionalFunctionExpressionPhase then
        inventNamesForLocalClassesPhase then
        kCallableNamePropertyPhase then
        annotationPhase then
        polymorphicSignaturePhase then
        varargPhase then
        arrayConstructorPhase then
        checkNotNullPhase then

        lateinitPhase then

        moveOrCopyCompanionObjectFieldsPhase then
        inlineCallableReferenceToLambdaPhase then
        propertyReferencePhase then
        constPhase then
        propertiesToFieldsPhase then
        remapObjectFieldAccesses then
        propertiesPhase then
        anonymousObjectSuperConstructorPhase then
        tailrecPhase then

        jvmInlineClassPhase then

        sharedVariablesPhase then

        enumWhenPhase then
        singletonReferencesPhase then

        callableReferencePhase then
        singleAbstractMethodPhase then
        assertionPhase then
        returnableBlocksPhase then
        localDeclarationsPhase then
        jvmLocalClassExtractionPhase then

        jvmOverloadsAnnotationPhase then
        jvmDefaultConstructorPhase then

        forLoopsPhase then
        flattenStringConcatenationPhase then
        foldConstantLoweringPhase then
        computeStringTrimPhase then
        jvmStringConcatenationLowering then

        defaultArgumentStubPhase then
        defaultArgumentInjectorPhase then

        interfacePhase then
        inheritedDefaultMethodsOnClassesPhase then
        interfaceSuperCallsPhase then
        interfaceDefaultCallsPhase then
        interfaceObjectCallsPhase then

        tailCallOptimizationPhase then
        addContinuationPhase then

        innerClassesPhase then
        innerClassConstructorCallsPhase then

        enumClassPhase then
        objectClassPhase then
        staticInitializersPhase then
        initializersPhase then
        collectionStubMethodLowering then
        functionNVarargBridgePhase then
        bridgePhase then
        jvmStaticAnnotationPhase then
        staticDefaultFunctionPhase then
        syntheticAccessorPhase then


        jvmArgumentNullabilityAssertions then
        toArrayPhase then
        jvmBuiltinOptimizationLoweringPhase then
        additionalClassAnnotationPhase then
        typeOperatorLowering then
        replaceKFunctionInvokeWithFunctionInvokePhase then

        checkLocalNamesWithOldBackendPhase then

        mainMethodGenerationPhase then
        renameFieldsPhase then
        fakeInliningLocalVariablesLowering

val jvmPhases = namedIrModulePhase(
    name = "IrLowering",
    description = "IR lowering",
    lower = validateIrBeforeLowering then
            expectDeclarationsRemovingPhase then
            fileClassPhase then
            performByIrFile(lower = jvmFilePhases) then
            generateMultifileFacadesPhase then
            resolveInlineCallsPhase then
            // should be last transformation
            removeDeclarationsThatWouldBeInlined then
            validateIrAfterLowering
)

class JvmLower(val context: JvmBackendContext) {
    fun lower(irModuleFragment: IrModuleFragment) {
        // TODO run lowering passes as callbacks in bottom-up visitor
        jvmPhases.invokeToplevel(context.phaseConfig, context, irModuleFragment)
    }
}
