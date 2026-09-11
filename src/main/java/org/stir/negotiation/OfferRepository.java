package org.stir.negotiation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferRepository extends JpaRepository<Offer, UUID> {
    List<Offer> findByNegotiationIdOrderBySequenceNumberAsc(UUID negotiationId);
    Optional<Offer> findByIdAndNegotiationId(UUID id, UUID negotiationId);
}
