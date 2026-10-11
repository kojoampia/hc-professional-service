package net.jojoaddison.domain.enumeration;

/**
 * The DocumentType enumeration.
 *
 * <h2>⛔ {@link #AVATAR} is a credential-shaped container for something that is not a credential</h2>
 *
 * <p>Every other value here names a document a reviewer gives a verdict on. {@code AVATAR} names the
 * clinician's profile photograph, stored through the same apparatus because that apparatus owns the
 * ceiling, the allow-list, the magic-byte check, the checksum and the owner-checked byte stream
 * (backlog.md row 232 § "Where the bytes live, settled"), and <b>it must count towards nothing</b>.
 *
 * <p>⚠ <b>Its own value is forced rather than preferred.</b> {@code REQ_PHOTO} is
 * {@code anyMatch(type == PASSPHOTO)} in two places in {@code OnboardingService}, so storing an avatar
 * as {@code PASSPHOTO} would let a selfie satisfy a mandatory credential and carry an application to
 * {@code CREDENTIAL_REVIEW}. Those predicates are positive — each names the type it wants — so this
 * value is excluded from them by construction and neither needed an edit.
 *
 * <p>⛔ <b>What did need an edit is the one aggregation that quantifies over documents WITHOUT naming
 * a type</b>: {@code OnboardingService.allLiveDocumentsVerified} is an {@code allMatch(VERIFIED)} over
 * a profile's live rows, behind the 409 that gates approval and behind the {@code documentsVerified}
 * flag on the {@code ProfileStatus} event. An avatar stored {@code PENDING} and left in that set would
 * have blocked approval of every clinician who uploaded one, for ever, with nobody able to review it.
 * See that method, and {@code OwnPersonalDocumentResource.uploadAvatar}.
 */
public enum DocumentType {
    PASSPORT,
    CERTIFICATE,
    LICENSE,
    GHANACARD,
    PASSPHOTO,
    DRIVERLICENSE,
    VOTERCARD,
    NHIS,
    OTHER,
    /**
     * The clinician's own profile photograph — not a credential, verified by nobody, and excluded from
     * every requirement and aggregation. Uploaded only through
     * {@code POST /api/personal-document/avatar}; both other create paths refuse it by name.
     */
    AVATAR,
}
