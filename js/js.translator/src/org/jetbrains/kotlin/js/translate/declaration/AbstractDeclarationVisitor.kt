/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.declaration

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.isOverridable
import org.jetbrains.kotlin.js.backend.ast.*
import org.jetbrains.kotlin.js.backend.ast.metadata.coroutineMetadata
import org.jetbrains.kotlin.js.backend.ast.metadata.isInlineableCoroutineBody
import org.jetbrains.kotlin.js.descriptorUtils.shouldBeExported
import org.jetbrains.kotlin.js.inline.util.FunctionWithWrapper
import org.jetbrains.kotlin.js.translate.context.TranslationContext
import org.jetbrains.kotlin.js.translate.expression.InlineMetadata
import org.jetbrains.kotlin.js.translate.expression.translateAndAliasParameters
import org.jetbrains.kotlin.js.translate.expression.translateFunction
import org.jetbrains.kotlin.js.translate.expression.wrapWithInlineMetadata
import org.jetbrains.kotlin.js.translate.general.TranslatorVisitor
import org.jetbrains.kotlin.js.translate.utils.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtensionProperty
import org.jetbrains.kotlin.resolve.source.getPsi

abstract class AbstractDeclarationVisitor : TranslatorVisitor<Unit>()  {
    override fun emptyResult(context: TranslationContext) { }

    open val enumInitializerName: JsName?
        get() = null

    override fun visitClassOrObject(classOrObject: KtClassOrObject, context: TranslationContext) {
        ClassTranslator.translate(classOrObject, context, enumInitializerName)
        val descriptor = BindingUtils.getClassDescriptor(context.bindingContext(), classOrObject)
        context.export(descriptor)
    }

    override fun visitProperty(expression: KtProperty, context: TranslationContext) {
        val descriptor = BindingUtils.getPropertyDescriptor(context.bindingContext(), expression)
        if (descriptor.modality === Modality.ABSTRACT) return

        val propertyContext = context.newDeclaration(descriptor)

        val defaultTranslator = DefaultPropertyTranslator(descriptor, context, getBackingFieldReference(descriptor))
        val getter = descriptor.getter!!
        val getterExpr = if (expression.hasCustomGetter()) {
            translateFunction(getter, expression.getter!!, propertyContext).first
        }
        else {
            val function = context.getFunctionObject(getter)
            function.source = expression
            defaultTranslator.generateDefaultGetterFunction(getter, function)
            function
        }

        val setterExpr = if (descriptor.isVar) {
            val setter = descriptor.setter!!
            if (expression.hasCustomSetter()) {
                translateFunction(setter, expression.setter!!, propertyContext).first
            }
            else {
                val function = context.getFunctionObject(setter)
                function.source = expression
                defaultTranslator.generateDefaultSetterFunction(setter, function)
                function
            }
        }
        else {
            null
        }

        if (TranslationUtils.shouldAccessViaFunctions(descriptor) || descriptor.isExtensionProperty) {
            addFunction(descriptor.getter!!, getterExpr, expression.getter ?: expression)
            descriptor.setter?.let { addFunction(it, setterExpr!!, expression.setter ?: expression) }
        }
        else {
            addProperty(descriptor, getterExpr, setterExpr)
        }
    }

    override fun visitNamedFunction(expression: KtNamedFunction, context: TranslationContext) {
        val descriptor = BindingUtils.getFunctionDescriptor(context.bindingContext(), expression)
        val functionAndContext = if (descriptor.modality != Modality.ABSTRACT) {
            translateFunction(descriptor, expression, context)
        }
        else {
            null
        }

        if (descriptor.isSuspend && descriptor.isInline && descriptor.shouldBeExported(context.config) && functionAndContext != null) {
            // Special case: function declaration should be split into two parts.
            // First: state machine, will be exported and potentially used without inlining
            // Second: Inliner-friendly declaration. Non-executable, used for inlining only.
            val innerContext = functionAndContext.second
            val inlineFunction = functionAndContext.first as JsFunction
            val exportedFunction = inlineFunction.deepCopy()

            // Prepare the noinline function, which will be transformed into a state machine and might be used from JS.
            // All imported JsName's should be replaced with global imports
            val block =
                innerContext.inlineFunctionContext!!.let {
                    JsBlock(
                        it.importBlock.deepCopy().statements +
                                it.prototypeBlock.deepCopy().statements +
                                it.declarationsBlock.deepCopy().statements +
                                JsReturn(exportedFunction)
                    )
                }
            addFunction(descriptor, InlineMetadata.wrapFunction(context, FunctionWithWrapper(exportedFunction, block), descriptor.source.getPsi()), expression)

//            val exportedFunction = innerContext.inlineFunctionContext!!.imports.entries.associate { (tag, name) ->
//                name.descriptor.let {
//                    name to context.getInnerNameForDescriptor(it)
//                } ?: {
//                    context.getInnerNameForDescriptor()
//                }
//            }.rename(inlineFunction.deepCopy())
//            addFunction(descriptor, exportedFunction, expression)

            // Yield the inline function declaration. Make sure it doesn't get transformed into a state machine.
            inlineFunction.name = null
            inlineFunction.coroutineMetadata = null
            inlineFunction.isInlineableCoroutineBody = true
            context.addDeclarationStatement(innerContext.wrapWithInlineMetadata(context, inlineFunction, descriptor).makeStmt())
        } else {
            addFunction(descriptor, functionAndContext?.first, expression)
        }
    }

    override fun visitTypeAlias(typeAlias: KtTypeAlias, data: TranslationContext?) {}

    private fun translateFunction(
            descriptor: FunctionDescriptor,
            expression: KtDeclarationWithBody,
            context: TranslationContext
    ): Pair<JsExpression, TranslationContext> {
        val function = context.getFunctionObject(descriptor)
        function.source = expression.finalElement
        val innerContext = context.newDeclaration(descriptor).translateAndAliasParameters(descriptor, function.parameters)

        if (descriptor.isSuspend) {
            function.fillCoroutineMetadata(innerContext, descriptor, hasController = false)
        }

        if (!descriptor.isOverridable) {
            function.body.statements += FunctionBodyTranslator.setDefaultValueForArguments(descriptor, innerContext)
        }
        innerContext.translateFunction(expression, function)
        val result = if (descriptor.isSuspend && descriptor.shouldBeExported(context.config)) {
            function
        }
        else {
            innerContext.wrapWithInlineMetadata(context, function, descriptor)
        }

        return Pair(result, innerContext)
    }

    // used from kotlinx.serialization
    abstract fun addFunction(
            descriptor: FunctionDescriptor,
            expression: JsExpression?,
            psi: KtElement?
    )

    // used from kotlinx.serialization
    abstract fun addProperty(
            descriptor: PropertyDescriptor,
            getter: JsExpression,
            setter: JsExpression?
    )

    // used from kotlinx.serialization
    abstract fun getBackingFieldReference(descriptor: PropertyDescriptor): JsExpression
}