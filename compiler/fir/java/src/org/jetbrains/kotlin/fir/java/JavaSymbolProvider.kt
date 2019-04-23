/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.java

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.declarations.FirTypeParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirTypeParameterImpl
import org.jetbrains.kotlin.fir.java.declarations.FirJavaClass
import org.jetbrains.kotlin.fir.java.declarations.FirJavaConstructor
import org.jetbrains.kotlin.fir.java.declarations.FirJavaField
import org.jetbrains.kotlin.fir.java.declarations.FirJavaMethod
import org.jetbrains.kotlin.fir.java.scopes.JavaClassEnhancementScope
import org.jetbrains.kotlin.fir.java.scopes.JavaClassUseSiteScope
import org.jetbrains.kotlin.fir.resolve.*
import org.jetbrains.kotlin.fir.resolve.transformers.firUnsafe
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirClassDeclaredMemberScope
import org.jetbrains.kotlin.fir.scopes.impl.FirSuperTypeScope
import org.jetbrains.kotlin.fir.symbols.CallableId
import org.jetbrains.kotlin.fir.symbols.ConeCallableSymbol
import org.jetbrains.kotlin.fir.symbols.ConeClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFieldSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeClassErrorType
import org.jetbrains.kotlin.fir.types.impl.ConeTypeParameterTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirResolvedTypeRefImpl
import org.jetbrains.kotlin.load.java.JavaClassFinder
import org.jetbrains.kotlin.load.java.JvmAnnotationNames
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.JavaTypeParameter
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade
import org.jetbrains.kotlin.types.Variance.INVARIANT

class JavaSymbolProvider(
    val session: FirSession,
    val project: Project,
    private val searchScope: GlobalSearchScope
) : AbstractFirSymbolProvider() {

    private val facade: KotlinJavaPsiFacade get() = KotlinJavaPsiFacade.getInstance(project)

    private fun findClass(classId: ClassId): JavaClass? = facade.findClass(JavaClassFinder.Request(classId), searchScope)

    override fun getTopLevelCallableSymbols(packageFqName: FqName, name: Name): List<ConeCallableSymbol> =
        emptyList()

    override fun getClassDeclaredMemberScope(classId: ClassId): FirScope? {
        val classSymbol = getClassLikeSymbolByFqName(classId) as? FirClassSymbol ?: return null
        return FirClassDeclaredMemberScope(classSymbol.fir)
    }

    override fun getClassUseSiteMemberScope(
        classId: ClassId,
        useSiteSession: FirSession,
        scopeSession: ScopeSession
    ): FirScope? {
        val symbol = this.getClassLikeSymbolByFqName(classId) ?: return null
        val javaClass = symbol.firUnsafe<FirJavaClass>()
        return buildJavaEnhancementScope(useSiteSession, javaClass.symbol, scopeSession)
    }

    private fun buildJavaEnhancementScope(
        useSiteSession: FirSession,
        symbol: FirClassSymbol,
        scopeSession: ScopeSession
    ): JavaClassEnhancementScope {
        return scopeSession.getOrBuild(symbol, JAVA_ENHANCEMENT) {
            JavaClassEnhancementScope(useSiteSession, buildJavaUseSiteScope(symbol.fir, useSiteSession, scopeSession))
        }
    }

    private fun buildJavaUseSiteScope(
        regularClass: FirRegularClass,
        useSiteSession: FirSession,
        scopeSession: ScopeSession
    ): JavaClassUseSiteScope {
        return scopeSession.getOrBuild(regularClass.symbol, JAVA_USE_SITE) {
            val declaredScope = scopeSession.getOrBuild(regularClass.symbol, DECLARED) { FirClassDeclaredMemberScope(regularClass) }
            val superTypeEnhancementScopes =
                lookupSuperTypes(regularClass, lookupInterfaces = true, deep = false, useSiteSession = useSiteSession)
                    .mapNotNull { useSiteSuperType ->
                        if (useSiteSuperType is ConeClassErrorType) return@mapNotNull null
                        val symbol = useSiteSuperType.lookupTag.toSymbol(useSiteSession)
                        if (symbol is FirClassSymbol) {
                            // We need JavaClassEnhancementScope here to have already enhanced signatures from supertypes
                            val scope = buildJavaEnhancementScope(useSiteSession, symbol, scopeSession)
                            useSiteSuperType.wrapSubstitutionScopeIfNeed(useSiteSession, scope, scopeSession)
                        } else {
                            null
                        }
                    }
            JavaClassUseSiteScope(regularClass, useSiteSession, FirSuperTypeScope(session, superTypeEnhancementScopes), declaredScope)
        }
    }

    private fun JavaTypeParameter.toFirTypeParameter(javaTypeParameterStack: JavaTypeParameterStack): FirTypeParameter {
        val stored = javaTypeParameterStack.safeGet(this)
        if (stored != null) return stored.fir
        val firSymbol = FirTypeParameterSymbol()
        val result = FirTypeParameterImpl(session, null, firSymbol, name, variance = INVARIANT, isReified = false)
        javaTypeParameterStack.add(this, result)
        return result
    }

    private fun FirTypeParameter.addBounds(
        javaTypeParameter: JavaTypeParameter,
        stack: JavaTypeParameterStack
    ) {
        require(this is FirTypeParameterImpl)
        for (upperBound in javaTypeParameter.upperBounds) {
            bounds += upperBound.toFirResolvedTypeRef(this@JavaSymbolProvider.session, stack)
        }
    }

    private fun List<JavaTypeParameter>.convertTypeParameters(stack: JavaTypeParameterStack): List<FirTypeParameter> {
        return this
            .map { it.toFirTypeParameter(stack) }
            .also {
                it.forEachIndexed { index, typeParameter ->
                    typeParameter.addBounds(this[index], stack)
                }
            }
    }

    override fun getClassLikeSymbolByFqName(classId: ClassId): ConeClassLikeSymbol? {
        if (!hasTopLevelClassOf(classId)) return null
        return classCache.lookupCacheOrCalculateWithPostCompute(classId, {
            val foundClass = findClass(classId)
            if (foundClass == null || foundClass.annotations.any { it.classId?.asSingleFqName() == JvmAnnotationNames.METADATA_FQ_NAME }) {
                null to null
            } else {
                FirClassSymbol(classId) to foundClass
            }
        }) { firSymbol, foundClass ->
            foundClass?.let { javaClass ->
                val javaTypeParameterStack = JavaTypeParameterStack()
                val parentFqName = classId.relativeClassName.parent()
                val isTopLevel = parentFqName.isRoot
                if (!isTopLevel) {
                    val parentId = ClassId(classId.packageFqName, parentFqName, false)
                    val parentClassSymbol = getClassLikeSymbolByFqName(parentId) as? FirClassSymbol
                    val parentClass = parentClassSymbol?.fir
                    if (parentClass is FirJavaClass) {
                        javaTypeParameterStack.addStack(parentClass.javaTypeParameterStack)
                    }
                }
                FirJavaClass(
                    session, firSymbol as FirClassSymbol, javaClass.name,
                    javaClass.visibility, javaClass.modality,
                    javaClass.classKind, isTopLevel = isTopLevel,
                    isStatic = javaClass.isStatic,
                    javaTypeParameterStack = javaTypeParameterStack
                ).apply {
                    this.typeParameters += foundClass.typeParameters.convertTypeParameters(javaTypeParameterStack)
                    addAnnotationsFrom(this@JavaSymbolProvider.session, javaClass, javaTypeParameterStack)
                    for (supertype in javaClass.supertypes) {
                        superTypeRefs += supertype.toFirResolvedTypeRef(this@JavaSymbolProvider.session, javaTypeParameterStack)
                    }
                    // TODO: may be we can process fields & methods later.
                    // However, they should be built up to override resolve stage
                    for (javaField in javaClass.fields) {
                        val fieldName = javaField.name
                        val fieldId = CallableId(classId.packageFqName, classId.relativeClassName, fieldName)
                        val fieldSymbol = FirFieldSymbol(fieldId)
                        val returnType = javaField.type
                        val firJavaField = FirJavaField(
                            this@JavaSymbolProvider.session, fieldSymbol, fieldName,
                            javaField.visibility, javaField.modality,
                            returnTypeRef = returnType.toFirJavaTypeRef(this@JavaSymbolProvider.session, javaTypeParameterStack),
                            isVar = !javaField.isFinal,
                            isStatic = javaField.isStatic
                        ).apply {
                            addAnnotationsFrom(this@JavaSymbolProvider.session, javaField, javaTypeParameterStack)
                        }
                        declarations += firJavaField
                    }
                    for (javaMethod in javaClass.methods) {
                        val methodName = javaMethod.name
                        val methodId = CallableId(classId.packageFqName, classId.relativeClassName, methodName)
                        val methodSymbol = FirFunctionSymbol(methodId)
                        val returnType = javaMethod.returnType
                        val firJavaMethod = FirJavaMethod(
                            this@JavaSymbolProvider.session, methodSymbol, methodName,
                            javaMethod.visibility, javaMethod.modality,
                            returnTypeRef = returnType.toFirJavaTypeRef(this@JavaSymbolProvider.session, javaTypeParameterStack),
                            isStatic = javaMethod.isStatic
                        ).apply {
                            this.typeParameters += javaMethod.typeParameters.convertTypeParameters(javaTypeParameterStack)
                            addAnnotationsFrom(this@JavaSymbolProvider.session, javaMethod, javaTypeParameterStack)
                            for (valueParameter in javaMethod.valueParameters) {
                                valueParameters += valueParameter.toFirValueParameters(
                                    this@JavaSymbolProvider.session, javaTypeParameterStack
                                )
                            }
                        }
                        declarations += firJavaMethod
                    }
                    for (javaConstructor in javaClass.constructors) {
                        val constructorId = CallableId(classId.packageFqName, classId.relativeClassName, classId.shortClassName)
                        val constructorSymbol = FirFunctionSymbol(constructorId)
                        val classTypeParameters = javaClass.typeParameters.convertTypeParameters(javaTypeParameterStack)
                        val constructorTypeParameters = javaConstructor.typeParameters.convertTypeParameters(javaTypeParameterStack)
                        val typeParameters = classTypeParameters + constructorTypeParameters
                        val firJavaConstructor = FirJavaConstructor(
                            this@JavaSymbolProvider.session, constructorSymbol, javaConstructor.visibility,
                            FirResolvedTypeRefImpl(
                                this@JavaSymbolProvider.session, null,
                                firSymbol.constructType(
                                    classTypeParameters.map { ConeTypeParameterTypeImpl(it.symbol, false) }.toTypedArray(), false
                                ),
                                false, emptyList()
                            )
                        ).apply {
                            this.typeParameters += typeParameters
                            addAnnotationsFrom(this@JavaSymbolProvider.session, javaConstructor, javaTypeParameterStack)
                            for (valueParameter in javaConstructor.valueParameters) {
                                valueParameters += valueParameter.toFirValueParameters(
                                    this@JavaSymbolProvider.session, javaTypeParameterStack
                                )
                            }
                        }
                        declarations += firJavaConstructor
                    }
                }
            }
        }
    }

    override fun getPackage(fqName: FqName): FqName? {
        return packageCache.lookupCacheOrCalculate(fqName) {
            val facade = KotlinJavaPsiFacade.getInstance(project)
            val javaPackage = facade.findPackage(fqName.asString(), searchScope) ?: return@lookupCacheOrCalculate null
            FqName(javaPackage.qualifiedName)
        }
    }

    fun getJavaTopLevelClasses(): List<FirRegularClass> {
        return classCache.values
            .filterIsInstance<FirClassSymbol>()
            .filter { it.classId.relativeClassName.parent().isRoot }
            .map { it.fir }
    }

    private val knownClassNamesInPackage = mutableMapOf<FqName, Set<String>?>()

    private fun hasTopLevelClassOf(classId: ClassId): Boolean {
        val knownNames = knownClassNamesInPackage.getOrPut(classId.packageFqName) {
            facade.knownClassNamesInPackage(classId.packageFqName)
        } ?: return false
        return classId.relativeClassName.topLevelName() in knownNames
    }
}

fun FqName.topLevelName() =
    asString().substringBefore(".")


private val JAVA_ENHANCEMENT = scopeSessionKey<JavaClassEnhancementScope>()
private val JAVA_USE_SITE = scopeSessionKey<JavaClassUseSiteScope>()