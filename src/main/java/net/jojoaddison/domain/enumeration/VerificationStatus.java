package net.jojoaddison.domain.enumeration;

/**
 * Credentialing verdict for an uploaded {@code PersonalDocument}
 * (onboarding workflow § Documents).
 */
public enum VerificationStatus {
    PENDING,
    VERIFIED,
    REJECTED,
}
