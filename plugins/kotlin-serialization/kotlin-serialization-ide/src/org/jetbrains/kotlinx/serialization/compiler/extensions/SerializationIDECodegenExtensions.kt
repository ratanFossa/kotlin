/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.extensions

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.codegen.ImplementationBodyCodegen
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.declaration.DeclarationBodyVisitor
import org.jetbrains.kotlin.psi.KtPureClassOrObject
import org.jetbrains.kotlinx.serialization.idea.runIfEnabledOn

class SerializationIDECodegenExtension : SerializationCodegenExtension() {
    override fun generateClassSyntheticParts(codegen: ImplementationBodyCodegen) =
        runIfEnabledOn(codegen.descriptor) { super.generateClassSyntheticParts(codegen) }
}

class SerializationIDEJsExtension : SerializationJsExtension() {
    override fun generateClassSyntheticParts(
        declaration: KtPureClassOrObject,
        descriptor: ClassDescriptor,
        translator: DeclarationBodyVisitor,
        context: TranslationContext
    ) = runIfEnabledOn(descriptor) {
        super.generateClassSyntheticParts(declaration, descriptor, translator, context)
    }
}

class SerializationIDEIrExtension : SerializationLoweringExtension() {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        /* No-op – don't enable IR extensions in IDE */
    }
}