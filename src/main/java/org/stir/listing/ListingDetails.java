package org.stir.listing;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;

/**
 * What is offered or needed beyond title and description (STRUCTURED_TERMS_AND_NEEDS.md): the
 * object's state, how it changes hands, what it includes, until when it is available, how far a
 * conversation got, and - for a need - by when, which dimensions can move and which cannot.
 * counterpartNote is shared only with the people negotiating on the listing. Optional as a whole:
 * a request without it describes a listing with none of these properties.
 */
public record ListingDetails(
    @Pattern(regexp="NEW|LIKE_NEW|GOOD|FAIR|FOR_PARTS") String itemCondition,
    @Pattern(regexp=FULFILMENT_MODES) String fulfilmentMode,
    @Size(max=1000) String includedItems,
    LocalDate availableUntil,
    @Pattern(regexp="AVAILABLE|IN_CONVERSATION|COMMITTED") String availability,
    LocalDate neededBy,
    @Size(max=5) List<@Pattern(regexp=DIMENSIONS) String> flexibleOn,
    @Size(max=5) List<@Pattern(regexp=DIMENSIONS) String> firmOn,
    @Size(max=2000) String counterpartNote
) {
    public static final String FULFILMENT_MODES="PICKUP|DELIVERY|PICKUP_OR_DELIVERY|ON_SITE|REMOTE";
    public static final String DIMENSIONS="TIMING|PLACE|QUANTITY|CONDITION|AMOUNT";
    public static final ListingDetails NONE=new ListingDetails(null,null,null,null,null,null,null,null,null);
}
