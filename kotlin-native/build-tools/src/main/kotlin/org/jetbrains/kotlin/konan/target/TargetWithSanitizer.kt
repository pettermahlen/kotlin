/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.target

import org.gradle.api.Named
import org.gradle.api.attributes.Attribute
import java.io.Serializable

/**
 * [Target][KonanTarget] with optional [sanitizer][SanitizerKind].
 *
 * Can be used as a gradle attribute: `attribute(TargetWithSanitizer.TARGET_ATTRIBUTE, target.withSanitizer())`
 */
class TargetWithSanitizer(
        val target: KonanTarget,
        val sanitizer: SanitizerKind?,
) : Named, Serializable {
    override fun getName(): String = "$target${sanitizer.targetSuffix}"

    override fun toString(): String = name

    override fun hashCode(): Int {
        return name.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        val otherTarget = other as? TargetWithSanitizer ?: return false
        return name == otherTarget.name
    }

    companion object {
        @JvmStatic
        val TARGET_ATTRIBUTE = Attribute.of("org.jetbrains.kotlin.target", TargetWithSanitizer::class.java)
    }
}

/**
 * Construct [TargetWithSanitizer] from [target][KonanTarget] and optional [sanitizer][SanitizerKind].
 */
fun KonanTarget.withSanitizer(sanitizer: SanitizerKind? = null) = TargetWithSanitizer(this, sanitizer)

/**
 * All known targets with their sanitizers.
 */
val PlatformManager.allTargetsWithSanitizers
    get() = this.enabled.flatMap { target ->
        listOf(target.withSanitizer()) + target.supportedSanitizers().map {
            target.withSanitizer(it)
        }
    }