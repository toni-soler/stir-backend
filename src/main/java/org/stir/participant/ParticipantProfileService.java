package org.stir.participant;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service @Transactional
public class ParticipantProfileService {
    private final ParticipantProfileRepository profiles;
    public ParticipantProfileService(ParticipantProfileRepository profiles) { this.profiles=profiles; }

    UUID tenant() {
        var context=TenantContext.get();
        if(context==null || context.getTenantId()==null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    UUID owner(CurrentUser user) {
        if(user==null || user.isService() || user.getUserId()==null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    public ParticipantProfileView me(CurrentUser user) {
        return profiles.findByTenantIdAndUserId(tenant(),owner(user)).map(ParticipantProfileView::of)
            .orElseThrow(()->new ResponseStatusException(NOT_FOUND,"No profile yet"));
    }
    public ParticipantProfileView view(UUID userId) {
        return profiles.findByTenantIdAndUserId(tenant(),userId).map(ParticipantProfileView::of)
            .orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Participant not found"));
    }
    public ParticipantProfileView upsertMe(CurrentUser user, ParticipantProfileRequest request) {
        UUID tenant=tenant(), owner=owner(user);
        var profile=profiles.findByTenantIdAndUserId(tenant,owner).orElseGet(()->{
            var created=new ParticipantProfile(); created.id=UUID.randomUUID(); created.tenantId=tenant; created.userId=owner;
            created.active=true; created.createdAt=Instant.now(); return created;
        });
        profile.displayName=request.displayName().trim();
        profile.bio=blankToNull(request.bio()); profile.location=blankToNull(request.location());
        profile.updatedAt=Instant.now();
        return ParticipantProfileView.of(profiles.saveAndFlush(profile));
    }
    private String blankToNull(String value) { return (value==null || value.isBlank()) ? null : value.trim(); }
}
