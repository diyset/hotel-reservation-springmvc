package com.demo.domain;

import javax.persistence.*;
import javax.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Entity
public class Reservation {
    public static final double TAX_AMOUNT = 0.10;
    public static final double CHILD_DISCOUNT_PERCENT = 0.60;

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    private UUID reservationId = UUID.randomUUID();

    @OneToOne(mappedBy = "reservation")
    private Room room;

    @ManyToMany(cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "reservation_guests",
            joinColumns = @JoinColumn(name = "reservation_id", referencedColumnName = "id"),
            inverseJoinColumns = @JoinColumn(name = "guest_id", referencedColumnName = "id")
    )
    private Set<Guest> guests = new HashSet<>();

    @Embedded
    @Valid
    private ReservationDates dates = new ReservationDates();

    // no CascadeType since Extra already has an id associated to it.
    @ManyToMany
    @JoinTable(
            name = "reservation_general_extras",
            joinColumns = @JoinColumn(name = "reservation_id", referencedColumnName = "id"),
            inverseJoinColumns = @JoinColumn(name = "general_extra_id", referencedColumnName = "id")
    )
    private Set<Extra> generalExtras = new HashSet<>();

    @OneToMany(cascade = CascadeType.ALL)
    private Set<MealPlan> mealPlans = new HashSet<>();

    @OneToMany(cascade = CascadeType.ALL)
    public Set<Payment> attemptedPayments = new HashSet<>();

    public LocalDateTime createdTime;

    /**
     * @return The time this {@code Reservation} was successfully paid for and persisted.
     */
    public LocalDateTime getCreatedTime() {
        return createdTime;
    }

    public void setCreatedTimeNow() {
        createdTime = LocalDateTime.now();
    }

    public Reservation() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    /**
     * Use the utility functions add/remove guest to perform changes.
     *
     * @return The unmodifiable set of {@code Guest}s.
     */
    public Set<Guest> getGuests() {
        return Collections.unmodifiableSet(guests);
    }

    /**
     * Add a guest only if the room has free beds.
     *
     * @param guest
     */
    public void addGuest(Guest guest) {
        if (!isRoomFull()) {
            guests.add(guest);
        }
    }

    public void clearGuests() {
        guests.clear();
    }

    /**
     * Allows UI to easily remove a {@code Guest} in the 'add guest' page. Its easier for the UI to 'POST' a guest id
     * rather than provide the full {@code Guest} details that match {@code Guest.equals/hashCode}.
     *
     * @param guestId The temporarily assigned guest id.
     * @return {@code true} if the {@code Guest} was removed otherwise {@code false}.
     */
    public boolean removeGuestById(UUID guestId) {
        return guests.removeIf(guest -> guest.getTempId().equals(guestId));
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Set<Extra> getGeneralExtras() {
        return generalExtras;
    }

    /**
     * All {@code Extra}s must be {@code Extra.Category.General} otherwise an IllegalArgumentException is thrown.
     *
     * @param generalExtras All {@code Extra}s must be {@code Extra.Category.General}.
     * @throws IllegalArgumentException
     */
    public void setGeneralExtras(Set<Extra> generalExtras) throws IllegalArgumentException {
        boolean containsInvalidCategories
                = generalExtras.stream().anyMatch(extra -> extra.getCategory() != Extra.Category.General);
        if (containsInvalidCategories) {
            throw new IllegalArgumentException("Contains invalid categories that are not Extra.Category.General");
        }
        this.generalExtras = generalExtras;
    }

    public Set<MealPlan> getMealPlans() {
        return mealPlans;
    }

    /**
     * @param mealPlans
     */
    public void setMealPlans(Set<MealPlan> mealPlans) {
        this.mealPlans = mealPlans;
    }

    public void resetMealPlans() {
        mealPlans = new HashSet<>();
    }

    public void resetExtras() {
        generalExtras = new HashSet<>();
    }

    public ReservationDates getDates() {
        return dates;
    }

    public void setDates(ReservationDates dates) {
        this.dates = dates;
    }

    public boolean isRoomFull() {
        return guests.size() >= room.getBeds();
    }

    public boolean hasGuests() {
        return !guests.isEmpty();
    }

    public boolean hasAtLeastOneAdultGuest() {
        return guests.stream().anyMatch(guest -> !guest.isChild());
    }

    /**
     * Calculates {@code Extra.Type} to correctly charge the food and general extras.
     *
     * @return Depending on the room type, return {@code Extra.Type.Premium/Basic}.
     */
    public Extra.Type getExtraPricingType() {
        switch (room.getRoomType()) {
            case Luxury:
            case Business:
                return Extra.Type.Premium;
            default:
                return Extra.Type.Basic;
        }
    }

    /**
     * Calculates the chargeable late fee price only if the user has selected the late checkout option.
     */
    public BigDecimal getChargeableLateCheckoutFee() {
        return dates.isLateCheckout() ? getLateCheckoutFee() : BigDecimal.ZERO;
    }

    /**
     * The late checkout fee depending on the type of room.
     * For the actual chargeable fee, use {@link #getChargeableLateCheckoutFee()}
     */
    public BigDecimal getLateCheckoutFee() {
        switch (room.getRoomType()) {
            case Luxury:
            case Business:
                return BigDecimal.ZERO;
            default:
                return room.getHotel().getLateCheckoutFee();
        }
    }

    /**
     * No late fee is considered.
     * Provided separately to allow break down to sub totals on invoices.
     *
     * @return Total nights * per night cost
     */
    public BigDecimal getTotalRoomCost() {
        long nights = dates.totalNights();
        if (nights == 0) {
            return BigDecimal.ZERO;
        }
        return room.getCostPerNight().multiply(BigDecimal.valueOf(nights));
    }

    /**
     * Provided separately to allow break down to sub totals on invoices.
     *
     * @return {@link #getTotalRoomCost} + {@link #getChargeableLateCheckoutFee}
     */
    public BigDecimal getTotalRoomCostWithLateCheckoutFee() {
        return getTotalRoomCost().add(getChargeableLateCheckoutFee());
    }

    /**
     * Calculates the total general extras cost.
     * Provided separately to allow break down to sub totals on invoices.
     * <p>
     * {@code Daily extra cost * total nights}
     */
    public BigDecimal getTotalGeneralExtrasCost() {
        long totalNights = dates.totalNights();
        return generalExtras.stream().reduce(
                BigDecimal.ZERO,
                (acc, next) -> acc.add(next.getTotalPrice(totalNights))
                , BigDecimal::add
        );
    }

    /**
     * Provided separately to allow break down to sub totals on invoices.
     * @return Total cost of all guests meal plans
     */
    public BigDecimal getTotalMealPlansCost() {
        return mealPlans.stream()
                .map(MealPlan::getTotalMealPlanCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add, BigDecimal::add);
    }

    /**
     * Total cost including everything!
     * Provided separately to allow break down to sub totals on invoices.
     */
    public BigDecimal getTotalCostExcludingTax() {
        return getTotalRoomCostWithLateCheckoutFee()
                .add(getTotalGeneralExtrasCost())
                .add(getTotalMealPlansCost());
    }

    /**
     * Provided separately to allow break down to sub totals on invoices.
     *
     * @return The taxable amount from the total cost. Eg 10% of $100 = $10.
     */
    public BigDecimal getTaxableAmount() {
        return getTotalCostExcludingTax().multiply(BigDecimal.valueOf(TAX_AMOUNT));
    }

    /**
     * Provided separately to allow break down to sub totals on invoices.
     *
     * @return The total cost including tax.
     */
    public BigDecimal getTotalCostIncludingTax() {
        return getTotalCostExcludingTax().add(getTaxableAmount());
    }
//
//    /**
//     * Useful for UI template rendering.
//     *
//     * @return List of sorted {@code MealPlan}s according to the {@code Guest} comparator.
//     */
//    public List<MealPlan> getSortedMealPlansByGuest() {
//        return mealPlans.stream()
//                .sorted(Comparator.comparing(MealPlan::getGuest, Guest.comparator()))
//                .collect(Collectors.toList());
//    }
//
//    /**
//     *
//     * @return List of sorted {@code Guest}s.
//     */
//    public List<Guest> getSortedGuests() {
//        return guests.stream()
//                .sorted(Guest.comparator())
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * Used by UI templates to decide when to include in the payment summary since
//     * only food extras have a payment associated with them unlike dietary requirements.
//     *
//     * @return {@code true} if there exists at least 1 meal plan which has a food extra such as
//     * breakfast, lunch or dinner.
//     */
//    public boolean hasMealPlansWithFoodExtras() {
//        return mealPlans.stream()
//                .anyMatch(MealPlan::hasFoodExtras);
//    }
//
//    /**
//     *
//     * @return {@code true} if the {@code MealPlan} has a food plan or diet requirement.
//     */
//    public boolean hasMealPlans() {
//        return mealPlans.stream()
//                .anyMatch(mealPlan -> mealPlan.hasFoodExtras() || mealPlan.hasDietRequirements());
//    }
//
//    /**
//     * A history of {@code Payment}s are kept since its possible a payment may be declined and reattempted which
//     * we capture for potential payment investigations.
//     *
//     * @param payment
//     */
//    public void addAttemptedPayment(Payment payment) {
//        attemptedPayments.add(payment);
//    }
//
//    public List<Guest> getPrimaryContacts() {
//        return guests.stream()
//                .filter(Guest::isPrimaryContact)
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public boolean equals(Object o) {
//        if (this == o) return true;
//        if (o == null || getClass() != o.getClass()) return false;
//        Reservation that = (Reservation) o;
//        return Objects.equals(reservationId, that.reservationId);
//    }
//
//    @Override
//    public int hashCode() {
//        return Objects.hash(reservationId);
//    }
//

    @Override
    public String toString() {
        return "Reservation{" +
                "room=" + room +
                '}';
    }

}
