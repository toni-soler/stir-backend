package org.stir.negotiation;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service @Transactional(readOnly = true)
public class AgreementService {
    private final AgreementRepository agreements;
    private final AgreementSnapshotRepository snapshots;
    public AgreementService(AgreementRepository agreements, AgreementSnapshotRepository snapshots) {
        this.agreements=agreements; this.snapshots=snapshots;
    }
    private UUID tenant() {
        var context=TenantContext.get();
        if(context==null || context.getTenantId()==null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID actor(CurrentUser user) {
        if(user==null || user.isService() || user.getUserId()==null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }
    public Page<Agreement> listMine(CurrentUser user, int page, int size) {
        return agreements.findForParty(tenant(),actor(user),PageRequest.of(page,size));
    }
    public AgreementDetail read(CurrentUser user, UUID id) {
        UUID tenant=tenant(), actor=actor(user);
        var agreement=agreements.findByIdAndTenantId(id,tenant).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Agreement not found"));
        if(!agreement.initiatorId.equals(actor) && !agreement.ownerId.equals(actor))
            throw new ResponseStatusException(NOT_FOUND,"Agreement not found");
        var snapshot=snapshots.findByAgreementId(agreement.id).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Agreement not found"));
        return AgreementDetail.of(agreement,snapshot);
    }
}
