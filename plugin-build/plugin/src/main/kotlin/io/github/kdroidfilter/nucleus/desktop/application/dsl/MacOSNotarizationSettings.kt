/*
 * Copyright 2020-2022 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.dsl

import io.github.kdroidfilter.nucleus.desktop.application.internal.NucleusProperties
import io.github.kdroidfilter.nucleus.internal.utils.nullableProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import javax.inject.Inject

abstract class MacOSNotarizationSettings {
    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Inject
    protected abstract val providers: ProviderFactory

    @get:Input
    @get:Optional
    val appleID: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.macNotarizationAppleID(providers))
        }

    @get:Input
    @get:Optional
    val password: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.macNotarizationPassword(providers))
        }

    @get:Input
    @get:Optional
    val teamID: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.macNotarizationTeamID(providers))
        }

    /**
     * Name of a `notarytool` keychain profile created via `xcrun notarytool store-credentials`.
     * When set, notarization uses `--keychain-profile <name>` instead of `--apple-id`/`--team-id`/password.
     * Mutually exclusive with the `appleID`/`password`/`teamID` mode.
     */
    @get:Input
    @get:Optional
    val keychainProfile: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.macNotarizationKeychainProfile(providers))
        }

    /**
     * Optional path to the keychain that stores the `keychainProfile` credentials.
     * Forwarded to `notarytool` as `--keychain <path>`. Only used when [keychainProfile] is set.
     */
    @get:Input
    @get:Optional
    val keychainPath: Property<String> =
        objects.nullableProperty<String>().apply {
            set(NucleusProperties.macNotarizationKeychainPath(providers))
        }

    @Deprecated("This option is no longer supported and got replaced by teamID", level = DeprecationLevel.ERROR)
    @get:Internal
    val ascProvider: Property<String> =
        objects.nullableProperty()
}
