package com.stravo.vpn.domain.importing

import com.stravo.vpn.data.profile.ProfileRepository
import com.stravo.vpn.domain.model.FormFactor

interface ProfileImporter {
    suspend fun importProfile(source: ProfileImportSource): ImportResult
}

class DefaultProfileImporter(
    private val formFactor: FormFactor,
    private val profileRepository: ProfileRepository,
    private val validator: ProfileValidator = ProfileValidator(),
) : ProfileImporter {
    override suspend fun importProfile(source: ProfileImportSource): ImportResult {
        val validated = validator.validate(source.value, formFactor)

        if (validated is ValidationResult.Invalid) {
            return ImportResult.Failure(validated.error)
        }

        val profile = (validated as ValidationResult.Valid).profile
        return try {
            profileRepository.save(profile)
            ImportResult.Success(profile.summary())
        } catch (_: Throwable) {
            ImportResult.Failure(ImportError.StorageFailure)
        }
    }
}
