package org.stir.listing;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.stir.participant.ParticipantProfileRepository;
import static org.springframework.http.HttpStatus.*;

@Service @Transactional
public class ListingService {
    private final ListingRepository listings;
    private final JdbcTemplate jdbc;
    private final ParticipantProfileRepository profiles;
    public ListingService(ListingRepository listings, JdbcTemplate jdbc, ParticipantProfileRepository profiles) {
        this.listings=listings; this.jdbc=jdbc; this.profiles=profiles;
    }

    private UUID tenant() {
        var context=TenantContext.get();
        if(context==null || context.getTenantId()==null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID owner(CurrentUser user) {
        if(user==null || user.isService() || user.getUserId()==null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }
    public Map<String,List<String>> catalogs() {
        return Map.of("categories",jdbc.queryForList("select code from stir.category order by code",String.class),
            "resourceKinds",jdbc.queryForList("select code from stir.resource_kind order by code",String.class));
    }
    private Listing entity(UUID id) {
        return listings.findByIdAndTenantId(id,tenant()).orElseThrow(()->new ResponseStatusException(NOT_FOUND,"Listing not found"));
    }
    public ListingView read(UUID id) {
        var listing=entity(id);
        return ListingView.of(listing,displayNames(List.of(listing.ownerId)).get(listing.ownerId));
    }
    public Page<ListingView> search(CurrentUser user, String q, String direction, String category, String resourceKind, String status, boolean mine, int page, int size) {
        UUID tenant=tenant();
        Specification<Listing> spec=(r,c,b)->b.equal(r.get("tenantId"),tenant);
        if(mine) { UUID owner=owner(user); spec=spec.and((r,c,b)->b.equal(r.get("ownerId"),owner)); }
        for(var filter : Map.of("direction",Objects.toString(direction,""),"category",Objects.toString(category,""),"resourceKind",Objects.toString(resourceKind,""),"status",Objects.toString(status,"")).entrySet()) {
            if(!filter.getValue().isBlank()) spec=spec.and((r,c,b)->b.equal(r.get(filter.getKey()),filter.getValue()));
        }
        if(q!=null && !q.isBlank()) {
            String pattern="%"+q.toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            spec=spec.and((r,c,b)->b.or(b.like(b.lower(r.get("title")),pattern,'\\'),b.like(b.lower(r.get("description")),pattern,'\\')));
        }
        var result=listings.findAll(spec,PageRequest.of(page,size,Sort.by(Sort.Order.desc("createdAt"),Sort.Order.asc("id"))));
        var names=displayNames(result.getContent().stream().map(row->row.ownerId).toList());
        return result.map(row->ListingView.of(row,names.get(row.ownerId)));
    }
    private Map<UUID,String> displayNames(Collection<UUID> ownerIds) {
        return profiles.findByTenantIdAndUserIdIn(tenant(),ownerIds).stream()
            .collect(Collectors.toMap(profile->profile.userId,profile->profile.displayName));
    }
    public Listing create(CurrentUser user, ListingRequest request) {
        if(request.version()!=null) throw new ResponseStatusException(BAD_REQUEST,"Version is assigned by server");
        var listing=new Listing(); listing.id=UUID.randomUUID(); listing.tenantId=tenant(); listing.ownerId=owner(user);
        listing.status="ACTIVE"; listing.createdAt=Instant.now(); apply(listing,request);
        return listings.saveAndFlush(listing);
    }
    public Listing update(UUID id, CurrentUser user, ListingRequest request) {
        var listing=owned(id,user);
        if(!"ACTIVE".equals(listing.status)) throw new ResponseStatusException(CONFLICT,"Listing is closed");
        checkVersion(listing,request.version()); apply(listing,request); return listings.saveAndFlush(listing);
    }
    public Listing close(UUID id, CurrentUser user, long version) {
        var listing=owned(id,user);
        if("CLOSED".equals(listing.status)) return listing;
        checkVersion(listing,version); listing.status="CLOSED"; listing.updatedAt=Instant.now(); return listings.saveAndFlush(listing);
    }
    private Listing owned(UUID id, CurrentUser user) {
        var listing=entity(id);
        if(!listing.ownerId.equals(owner(user))) throw new AccessDeniedException("Only the owner may change this listing");
        return listing;
    }
    private void checkVersion(Listing listing, Long version) {
        if(version==null || listing.version!=version) throw new ResponseStatusException(CONFLICT,"Listing changed; reload before editing");
    }
    private void apply(Listing listing, ListingRequest request) {
        var catalogs=catalogs();
        if(!catalogs.get("categories").contains(request.category()) || !catalogs.get("resourceKinds").contains(request.resourceKind()))
            throw new ResponseStatusException(BAD_REQUEST,"Unknown category or resource kind");
        listing.direction=request.direction(); listing.title=request.title().trim(); listing.description=request.description().trim();
        listing.category=request.category(); listing.resourceKind=request.resourceKind();
        listing.location=request.location()==null?null:request.location().trim(); listing.updatedAt=Instant.now();
    }
}
