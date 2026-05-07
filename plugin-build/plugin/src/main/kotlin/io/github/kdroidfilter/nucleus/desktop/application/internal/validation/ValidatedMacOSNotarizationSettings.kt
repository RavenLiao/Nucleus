/*
 * Copyright 2020-2021 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package io.github.kdroidfilter.nucleus.desktop.application.internal.validation

import io.github.kdroidfilter.nucleus.desktop.application.dsl.MacOSNotarizationSettings
import io.github.kdroidfilter.nucleus.desktop.application.internal.NucleusProperties

internal sealed class NotarizationAuth {
    data class AppleId(
        val appleID: String,
        val password: String,
        val teamID: String,
    ) : NotarizationAuth()

    data class KeychainProfile(
        val profileName: String,
        val keychainPath: String?,
    ) : NotarizationAuth()
}

/**
 * Builds the `notarytool` authentication arguments and an optional stdin payload
 * (the Apple ID password is fed via stdin to keep it off the command line).
 */
internal fun NotarizationAuth.toNotaryToolArgs(): Pair<List<String>, String?> =
    when (this) {
        is NotarizationAuth.AppleId ->
            listOf("--apple-id", appleID, "--team-id", teamID) to password
        is NotarizationAuth.KeychainProfile ->
            buildList {
                add("--keychain-profile")
                add(profileName)
                keychainPath?.let {
                    add("--keychain")
                    add(it)
                }
            } to null
    }

internal data class ValidatedMacOSNotarizationSettings(val auth: NotarizationAuth)

internal fun MacOSNotarizationSettings?.validate(): ValidatedMacOSNotarizationSettings {
    checkNotNull(this) {
        ERR_NOTARIZATION_SETTINGS_ARE_NOT_PROVIDED
    }

    val appleId = appleID.orNull?.takeUnless { it.isEmpty() }
    val pwd = password.orNull?.takeUnless { it.isEmpty() }
    val team = teamID.orNull?.takeUnless { it.isEmpty() }
    val profile = keychainProfile.orNull?.takeUnless { it.isEmpty() }
    val keychainPathValue = keychainPath.orNull?.takeUnless { it.isEmpty() }

    val appleIdMode = appleId != null || pwd != null || team != null
    val keychainMode = profile != null

    check(!(appleIdMode && keychainMode)) {
        ERR_MUTUALLY_EXCLUSIVE
    }
    check(appleIdMode || keychainMode) {
        ERR_NO_MODE_CONFIGURED
    }

    return if (profile != null) {
        ValidatedMacOSNotarizationSettings(
            NotarizationAuth.KeychainProfile(
                profileName = profile,
                keychainPath = keychainPathValue,
            ),
        )
    } else {
        checkNotNull(appleId) { ERR_APPLE_ID_IS_EMPTY }
        checkNotNull(pwd) { ERR_PASSWORD_IS_EMPTY }
        checkNotNull(team) { ERR_TEAM_ID_IS_EMPTY }
        ValidatedMacOSNotarizationSettings(
            NotarizationAuth.AppleId(
                appleID = appleId,
                password = pwd,
                teamID = team,
            ),
        )
    }
}

private const val ERR_PREFIX = "Notarization settings error:"
private const val ERR_NOTARIZATION_SETTINGS_ARE_NOT_PROVIDED =
    "$ERR_PREFIX notarization settings are not provided"
private val ERR_NO_MODE_CONFIGURED =
    """|$ERR_PREFIX no authentication mode configured. Configure one of:
       |  * Apple ID mode: appleID + password + teamID
       |    (Gradle properties: ${NucleusProperties.MAC_NOTARIZATION_APPLE_ID},
       |     ${NucleusProperties.MAC_NOTARIZATION_PASSWORD},
       |     ${NucleusProperties.MAC_NOTARIZATION_TEAM_ID_PROVIDER});
       |  * Keychain profile mode: keychainProfile (created via 'xcrun notarytool store-credentials')
       |    (Gradle property: ${NucleusProperties.MAC_NOTARIZATION_KEYCHAIN_PROFILE});
    """.trimMargin()
private val ERR_MUTUALLY_EXCLUSIVE =
    """|$ERR_PREFIX appleID/password/teamID and keychainProfile are mutually exclusive.
       |Configure only one authentication mode.
    """.trimMargin()
private val ERR_APPLE_ID_IS_EMPTY =
    """|$ERR_PREFIX appleID is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_APPLE_ID}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.appleID' DSL property;
    """.trimMargin()
private val ERR_PASSWORD_IS_EMPTY =
    """|$ERR_PREFIX password is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_PASSWORD}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.password' DSL property;
    """.trimMargin()
private val ERR_TEAM_ID_IS_EMPTY =
    """|$ERR_PREFIX teamID is null or empty. To specify:
               |  * Use '${NucleusProperties.MAC_NOTARIZATION_TEAM_ID_PROVIDER}' Gradle property;
               |  * Or use 'nativeDistributions.macOS.notarization.teamID' DSL property;
    """.trimMargin()
