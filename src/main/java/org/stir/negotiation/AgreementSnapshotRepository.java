package org.stir.negotiation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementSnapshotRepository extends JpaRepository<AgreementSnapshot, UUID> {
    Optional<AgreementSnapshot> findByAgreementId(UUID agreementId);
}
