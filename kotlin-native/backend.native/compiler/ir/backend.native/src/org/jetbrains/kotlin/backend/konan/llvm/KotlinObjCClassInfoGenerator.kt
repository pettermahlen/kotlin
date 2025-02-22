/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.llvm

import llvm.LLVMLinkage
import llvm.LLVMSetLinkage
import llvm.LLVMValueRef
import org.jetbrains.kotlin.backend.konan.*
import org.jetbrains.kotlin.backend.konan.descriptors.getAnnotationStringValue
import org.jetbrains.kotlin.backend.konan.ir.*
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

internal class KotlinObjCClassInfoGenerator(override val generationState: NativeGenerationState) : ContextUtils {
    fun generate(irClass: IrClass) {
        assert(irClass.isFinalClass)

        val objCLLvmDeclarations = generationState.llvmDeclarations.forClass(irClass).objCDeclarations!!

        val instanceMethods = generateInstanceMethodDescs(irClass)

        val companionObject = irClass.companionObject()
        val classMethods = companionObject?.generateMethodDescs().orEmpty()

        val superclassName = irClass.getSuperClassNotAny()!!.let {
            llvm.imports.add(it.llvmSymbolOrigin)
            it.descriptor.getExternalObjCClassBinaryName()
        }
        val protocolNames = irClass.getSuperInterfaces().map {
            llvm.imports.add(it.llvmSymbolOrigin)
            it.name.asString().removeSuffix("Protocol")
        }

        val exportedClassName = selectExportedClassName(irClass)
        val className = exportedClassName ?: selectInternalClassName(irClass)

        val classNameLiteral = className?.let { staticData.cStringLiteral(it) } ?: NullPointer(llvm.int8Type)
        val info = Struct(runtime.kotlinObjCClassInfo,
                          classNameLiteral,
                          llvm.constInt32(if (exportedClassName != null) 1 else 0),

                          staticData.cStringLiteral(superclassName),
                          staticData.placeGlobalConstArray("", llvm.int8PtrType,
                        protocolNames.map { staticData.cStringLiteral(it) } + NullPointer(llvm.int8Type)),

                          staticData.placeGlobalConstArray("", runtime.objCMethodDescription, instanceMethods),
                          llvm.constInt32(instanceMethods.size),

                          staticData.placeGlobalConstArray("", runtime.objCMethodDescription, classMethods),
                          llvm.constInt32(classMethods.size),

                          objCLLvmDeclarations.bodyOffsetGlobal.pointer,

                          irClass.typeInfoPtr,
                          companionObject?.typeInfoPtr ?: NullPointer(runtime.typeInfoType),

                          staticData.placeGlobal(
                        "kobjcclassptr:${irClass.fqNameForIrSerialization}#internal",
                        NullPointer(llvm.int8Type)
                ).pointer,

                          generateClassDataImp(irClass)
        )

        objCLLvmDeclarations.classInfoGlobal.setInitializer(info)

        objCLLvmDeclarations.bodyOffsetGlobal.setInitializer(llvm.constInt32(0))
    }

    private fun IrClass.generateMethodDescs(): List<ObjCMethodDesc> = this.generateImpMethodDescs()

    private fun generateInstanceMethodDescs(
            irClass: IrClass
    ): List<ObjCMethodDesc> = mutableListOf<ObjCMethodDesc>().apply {
        addAll(irClass.generateMethodDescs())
        val allImplementedSelectors = this.map { it.selector }.toSet()

        assert(irClass.getSuperClassNotAny()!!.isExternalObjCClass())
        val allInitMethodsInfo = irClass.getSuperClassNotAny()!!.constructors
                .mapNotNull { it.getObjCInitMethod()?.getExternalObjCMethodInfo() }
                .filter { it.selector !in allImplementedSelectors }
                .distinctBy { it.selector }

        allInitMethodsInfo.mapTo(this) {
            ObjCMethodDesc(it.selector, it.encoding, llvm.missingInitImp.llvmValue)
        }
    }

    private fun selectExportedClassName(irClass: IrClass): String? {
        val exportObjCClassAnnotation = context.interopBuiltIns.exportObjCClass.fqNameSafe
        val explicitName = irClass.getAnnotationArgumentValue<String>(exportObjCClassAnnotation, "name")
        if (explicitName != null) return explicitName

        return if (irClass.annotations.hasAnnotation(exportObjCClassAnnotation)) irClass.name.asString() else null
    }

    private fun selectInternalClassName(irClass: IrClass): String? = if (irClass.isExported()) {
        irClass.fqNameForIrSerialization.asString()
    } else {
        null // Generate as anonymous.
    }

    private val impType = pointerType(functionType(llvm.int8PtrType, true, llvm.int8PtrType, llvm.int8PtrType))

    private inner class ObjCMethodDesc(
            val selector: String, val encoding: String, val impFunction: LLVMValueRef
    ) : Struct(
            runtime.objCMethodDescription,
            constPointer(impFunction).bitcast(impType),
            staticData.cStringLiteral(selector),
            staticData.cStringLiteral(encoding)
    )

    private fun IrClass.generateImpMethodDescs(): List<ObjCMethodDesc> = this.declarations
            .filterIsInstance<IrSimpleFunction>()
            .mapNotNull {
                val annotation =
                        it.annotations.findAnnotation(context.interopBuiltIns.objCMethodImp.fqNameSafe) ?:
                                return@mapNotNull null

                ObjCMethodDesc(
                        annotation.getAnnotationStringValue("selector"),
                        annotation.getAnnotationStringValue("encoding"),
                        it.llvmFunction.llvmValue
                )
            }

    private fun generateClassDataImp(irClass: IrClass): ConstPointer {
        val classDataPointer = staticData.placeGlobal(
                "kobjcclassdata:${irClass.fqNameForIrSerialization}#internal",
                Zero(runtime.kotlinObjCClassData)
        ).pointer

        val functionType = functionType(classDataPointer.llvmType, false, llvm.int8PtrType, llvm.int8PtrType)
        val functionName = "kobjcclassdataimp:${irClass.fqNameForIrSerialization}#internal"

        val function = generateFunctionNoRuntime(codegen, functionType, functionName) {
            ret(classDataPointer.llvm)
        }.also {
            LLVMSetLinkage(it, LLVMLinkage.LLVMPrivateLinkage)
        }

        return constPointer(function)
    }

    private val codegen = CodeGenerator(generationState)

    companion object {
        const val createdClassFieldIndex = 11
    }
}

internal fun CodeGenerator.kotlinObjCClassInfo(irClass: IrClass): LLVMValueRef {
    require(irClass.isKotlinObjCClass())
    return if (isExternal(irClass)) {
        importGlobal(
                irClass.kotlinObjCClassInfoSymbolName,
                runtime.kotlinObjCClassInfo,
                origin = irClass.llvmSymbolOrigin
        )
    } else {
        llvmDeclarations.forClass(irClass).objCDeclarations!!.classInfoGlobal.llvmGlobal
    }
}
