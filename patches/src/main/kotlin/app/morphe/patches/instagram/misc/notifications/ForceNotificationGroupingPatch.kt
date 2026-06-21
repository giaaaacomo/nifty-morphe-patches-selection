/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.instagram.misc.notifications

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.instagram.misc.extension.sharedExtensionPatch
import app.morphe.patches.instagram.shared.Constants.COMPATIBILITY_INSTAGRAM
import app.morphe.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val MODE_ALL = "all"
private const val MODE_CATEGORY = "category"

private const val FRAMEWORK_NOTIFICATION_BUILDER =
    "Landroid/app/Notification\$Builder;"

private val compatNotificationBuilders = setOf(
    "Landroidx/core/app/NotificationCompat\$Builder;",
    "Landroid/support/v4/app/NotificationCompat\$Builder;",
)

@Suppress("unused")
val forceNotificationGroupingPatch = bytecodePatch(
    name = "Force notification grouping",
    description = "Forces Instagram notifications into one group, or into separate groups by notification category.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)

    val groupingMode = stringOption(
        key = "groupingMode",
        default = MODE_ALL,
        values = mapOf(
            MODE_ALL to "All notifications",
            MODE_CATEGORY to "By category",
        ),
        title = "Grouping mode",
        description = "Choose whether every Instagram notification uses one notification group or separate groups by category.",
        required = true,
    ) { value ->
        value == MODE_ALL || value == MODE_CATEGORY
    }

    val debugLogging = booleanOption(
        key = "debugLogging",
        default = false,
        title = "Debug logging",
        description = "Print notification grouping decisions to logcat.",
    )

    dependsOn(sharedExtensionPatch)

    execute {
        GroupingModeFingerprint.method.returnEarly(groupingMode.value!!)
        DebugLoggingFingerprint.method.returnEarly(debugLogging.value!!)

        var patchedCallSites = 0

        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/extension/")) {
                return@classDefForEach
            }

            val mutableClassDef by lazy {
                mutableClassDefBy(classDef)
            }

            classDef.methods.forEach { method ->
                val implementation = method.implementation ?: return@forEach

                val hookTargets = implementation.instructions
                    .mapIndexedNotNull { index, instruction ->
                        notificationBuilderHookTarget(index, instruction)
                    }

                if (hookTargets.isEmpty()) {
                    return@forEach
                }

                val mutableMethod by lazy {
                    mutableClassDef.findMutableMethodOf(method)
                }

                hookTargets.asReversed().forEach { target ->
                    mutableMethod.addInstructions(
                        target.index,
                        target.instructions
                    )
                    patchedCallSites++
                }
            }
        }

        if (patchedCallSites == 0) {
            throw PatchException("No Instagram notification builder build() calls were found.")
        }
    }
}

private data class HookTarget(
    val index: Int,
    val instructions: String,
)

private fun notificationBuilderHookTarget(index: Int, instruction: Instruction): HookTarget? {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL) {
        return null
    }

    val reference = (instruction as? ReferenceInstruction)
        ?.reference as? MethodReference
        ?: return null

    if (
        reference.name != "build" ||
        reference.returnType != "Landroid/app/Notification;" ||
        reference.parameterTypes.isNotEmpty()
    ) {
        return null
    }

    val builderRegister = when (instruction) {
        is FiveRegisterInstruction -> instruction.registerC
        is RegisterRangeInstruction -> instruction.startRegister
        else -> return null
    }

    return when (reference.definingClass) {
        FRAMEWORK_NOTIFICATION_BUILDER -> HookTarget(
            index,
            """
                invoke-static/range { v$builderRegister .. v$builderRegister }, $EXTENSION_CLASS->apply(Landroid/app/Notification${'$'}Builder;)Landroid/app/Notification${'$'}Builder;
                move-result-object v$builderRegister
            """
        )

        in compatNotificationBuilders -> HookTarget(
            index,
            """
                invoke-static/range { v$builderRegister .. v$builderRegister }, $EXTENSION_CLASS->applyCompatBuilder(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$builderRegister
                check-cast v$builderRegister, ${reference.definingClass}
            """
        )

        else -> null
    }
}
