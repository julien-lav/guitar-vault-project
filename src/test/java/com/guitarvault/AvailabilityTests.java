package com.guitarvault;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Transactional
class AvailabilityTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired LuthierProfileRepository workshops;
    @Autowired AvailabilityService availability;
    @Autowired WorkshopAvailabilityRepository settings;
    @Autowired WorkshopBlockedDateRepository blocked;
    @Autowired AppointmentService appointments;
    @Autowired LuthierAppointmentRepository records;
    @MockitoBean JavaMailSender mail;
    MockMvc mvc;
    User owner, musician, other, admin;
    LuthierProfile workshop;
    LocalDate date;
    List<String> allDays = Arrays.stream(DayOfWeek.values()).map(Enum::name).toList();
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = account("calendar-luthier"); musician = account("calendar-musician"); other = account("calendar-other"); admin = account("calendar-admin");
        admin.setRoles(Set.of(Role.ROLE_ADMIN)); users.flush();
        workshop = new LuthierProfile(owner); workshop.update("Calendar workshop", "Paris", "Setups", "About", "contact", true); workshops.saveAndFlush(workshop);
        date = LocalDate.now(ZoneOffset.UTC).plusDays(7);
    }
    @Test void settingsGenerateOnlyWholeAppointmentsOnWorkingDaysAndKeepWorkshopTimeZone() throws Exception {
        availability.save(owner, -1, true, "Europe/Paris", 45, "09:00", "12:00", List.of(date.getDayOfWeek().name()));
        var calendar = day(); assertThat(calendar.slots()).hasSize(4);
        assertThat(calendar.slots().getFirst().start()).isEqualTo(date.atTime(9,0).atZone(ZoneId.of("Europe/Paris")).toInstant());
        assertThat(calendar.slots().getLast().localEnd()).isEqualTo(LocalTime.NOON);
        assertThat(availability.calendar(workshop,date.plusDays(1)).slots()).isEmpty();
        var a = appointments.create(musician, workshop.getId(), form(calendar.slots().getFirst().start()));
        assertThat(a.getTimeZone()).isEqualTo("Europe/Paris"); assertThat(a.getDurationMinutes()).isEqualTo(45);
        mvc.perform(get("/luthiers/"+workshop.getId()+"/appointments/new").with(user(musician.getEmail())).param("date",date.toString()).param("lang","en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("id=\"appointment-slot\"")))
                .andExpect(content().string(not(containsString("id=\"appointment-time\""))))
                .andExpect(content().string(not(containsString("??"))));
    }
    @Test void pendingRequestsDoNotReserveButConfirmationAndCancellationChangeFreeSlots() {
        configure(30);
        var start = day().slots().getFirst().start();
        var first = appointments.create(musician, workshop.getId(),form(start));
        var second = appointments.create(other, workshop.getId(),form(start));
        assertThat(day().slots()).hasSize(6);
        appointments.resolve(owner,first.getId(),first.getVersion(),"confirm");
        assertThat(day().slots()).hasSize(5);
        assertThatThrownBy(() -> appointments.resolve(owner,second.getId(),second.getVersion(),"confirm"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("availability.taken");
        assertThat(second.getStatus()).isEqualTo(LuthierAppointment.Status.PENDING);
        appointments.resolve(musician,first.getId(),first.getVersion(),"cancel");
        assertThat(day().slots()).hasSize(6);
        appointments.resolve(owner,second.getId(),second.getVersion(),"confirm");
        assertThat(day().slots()).hasSize(5);
    }
    @Test void durationChangesNeverRewriteConfirmedAppointmentsAndExcludePartialOverlaps() {
        configure(30); var start = day().slots().getFirst().start();
        var a = appointments.create(musician,workshop.getId(),form(start)); appointments.resolve(owner,a.getId(),a.getVersion(),"confirm");
        availability.save(owner,version(),true,"UTC",15,"09:00","12:00",allDays);
        assertThat(a.getDurationMinutes()).isEqualTo(30); assertThat(a.getEndsAt()).isEqualTo(start.plusSeconds(1800));
        assertThat(day().slots()).extracting(AvailabilityService.Slot::start)
                .doesNotContain(start,start.plusSeconds(900)).contains(start.plusSeconds(1800));
    }
    @Test void blockedDatesCanBeRestoredAndCannotHideAConfirmedBooking() {
        configure(30); availability.block(owner,version(),date); assertThat(day().slots()).isEmpty();
        availability.unblock(owner,version(),date); assertThat(day().slots()).hasSize(6);
        var a = appointments.create(musician,workshop.getId(),form(day().slots().getFirst().start()));
        appointments.resolve(owner,a.getId(),a.getVersion(),"confirm");
        assertThatThrownBy(() -> availability.block(owner,version(),date)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("availability.hasBooking");
        assertThat(blocked.findByWorkshopIdOrderByDateAsc(workshop.getId())).isEmpty();
    }
    @Test void oldSettingsFormsAndForgedOrOutOfHorizonSlotsAreRejected() {
        configure(30); long previous=version(); var start=day().slots().getFirst().start(); var oldForm=form(start);
        availability.save(owner,version(),true,"UTC",60,"09:00","12:00",allDays);
        assertThatThrownBy(() -> availability.save(owner,previous,true,"UTC",30,"09:00","12:00",allDays))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("availability.changed");
        assertThatThrownBy(() -> appointments.create(musician,workshop.getId(),oldForm)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("availability.changed");
        assertThatThrownBy(() -> appointments.create(musician,workshop.getId(),form(start.plusSeconds(60)))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> appointments.create(musician,workshop.getId(),form(start.plus(Duration.ofDays(100))))).isInstanceOf(ResponseStatusException.class);
        assertThat(records.count()).isZero();
    }
    @Test void changesBetweenProposalAndConfirmationRequireAValidNewProposal() {
        configure(30); var a=appointments.create(musician,workshop.getId(),form(day().slots().getFirst().start()));
        availability.block(owner,version(),date);
        assertThatThrownBy(() -> appointments.resolve(owner,a.getId(),a.getVersion(),"confirm")).isInstanceOf(ResponseStatusException.class);
        var tomorrow=availability.calendar(workshop,date.plusDays(1));
        appointments.propose(owner,a.getId(),a.getVersion(),form(tomorrow.slots().getFirst().start()));
        appointments.resolve(musician,a.getId(),a.getVersion(),"confirm");
        assertThat(a.getStatus()).isEqualTo(LuthierAppointment.Status.CONFIRMED);
    }
    @Test void ownerCalendarIsPrivateAndBookingFormsNeverRevealOtherPeoplesNamesOrSubjects() throws Exception {
        configure(30); var start=day().slots().getFirst().start(); var data=new HashMap<>(form(start)); data.put("title","SECRET OTHER VISIT");
        var a=appointments.create(musician,workshop.getId(),data); appointments.resolve(owner,a.getId(),a.getVersion(),"confirm");
        mvc.perform(get("/workshop/availability").with(user(owner.getEmail())).param("date",date.toString()).param("lang","fr"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(content().string(containsString("SECRET OTHER VISIT"))).andExpect(content().string(not(containsString("??"))));
        mvc.perform(get("/luthiers/"+workshop.getId()+"/appointments/new").with(user(other.getEmail())).param("date",date.toString()))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("SECRET OTHER VISIT"))))
                .andExpect(content().string(not(containsString("value=\""+start+"\""))));
        for (User actor:List.of(other,admin)) {
            mvc.perform(get("/workshop/availability").with(user(actor.getEmail()))).andExpect(status().isNotFound());
            mvc.perform(post("/workshop/availability/blocks").with(user(actor.getEmail())).with(csrf()).param("version",Long.toString(version())).param("date",date.plusDays(1).toString()))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(get("/workshop/availability")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/workshop/availability/blocks").with(user(owner.getEmail())).param("version",Long.toString(version())).param("date",date.toString()))
                .andExpect(status().isForbidden());
    }
    @Test void legacyAppointmentsWithoutADurationStillReserveThirtyMinutes() {
        Instant start=date.atTime(10,0).toInstant(ZoneOffset.UTC);
        var legacy=records.saveAndFlush(new LuthierAppointment(musician,workshop,"Legacy visit",start,"UTC"));
        appointments.resolve(owner,legacy.getId(),legacy.getVersion(),"confirm");
        configure(15);
        assertThat(day().slots()).extracting(AvailabilityService.Slot::start)
                .doesNotContain(start,start.plusSeconds(900)).contains(start.plusSeconds(1800));
        assertThat(legacy.getDurationMinutes()).isEqualTo(30);
    }
    @Test void invalidHoursDaysAndDurationDoNotReplaceTheExistingSettings() {
        configure(30);
        assertThatThrownBy(() -> availability.save(owner,version(),true,"UTC",30,"17:00","09:00",allDays)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> availability.save(owner,version(),true,"UTC",17,"09:00","12:00",allDays)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> availability.save(owner,version(),true,"UTC",30,"09:00","12:00",List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.findById(workshop.getId()).orElseThrow().getDurationMinutes()).isEqualTo(30);
    }
    @Test void clockChangeSlotsHaveExactDurationsAndNeverUseAmbiguousLocalTimes() {
        availability.save(owner,-1,true,"Europe/Paris",30,"01:00","04:00",allDays);
        var zone=ZoneId.of("Europe/Paris"); var transition=zone.getRules().nextTransition(Instant.now());
        for (int n=0;n<2;n++) {
            var target=transition.getInstant().atZone(zone).toLocalDate();
            var fixed=Clock.fixed(transition.getInstant().minus(Duration.ofDays(10)),ZoneOffset.UTC);
            var probe=new AvailabilityService(workshops,settings,blocked,records,fixed);
            var slots=probe.calendar(workshop,target).slots();
            assertThat(slots).isNotEmpty();
            for (var slot:slots) {
                assertThat(Duration.between(slot.start(),slot.end())).isEqualTo(Duration.ofMinutes(30));
                assertThat(zone.getRules().getValidOffsets(target.atTime(slot.localStart()))).hasSize(1);
                assertThat(zone.getRules().getValidOffsets(target.atTime(slot.localEnd()))).hasSize(1);
            }
            transition=zone.getRules().nextTransition(transition.getInstant().plusSeconds(1));
        }
    }
    @Test void weekdayHoursAndBreaksGenerateOnlyValidWindowsAndPreserveBookings() throws Exception {
        configure(30); var dayName = date.getDayOfWeek().name(); var tomorrow = date.plusDays(1).getDayOfWeek().name();
        Map<String, String> hours = new HashMap<>();
        hours.put(dayName + "-opens", "08:00"); hours.put(dayName + "-closes", "17:00");
        hours.put(dayName + "-breakStart", "12:00"); hours.put(dayName + "-breakEnd", "13:15");
        hours.put(tomorrow + "-opens", "10:00"); hours.put(tomorrow + "-closes", "12:00");
        availability.saveHours(owner, version(), List.of(dayName, tomorrow), hours);
        assertThat(day().slots()).extracting(AvailabilityService.Slot::localStart)
                .contains(LocalTime.of(8,0), LocalTime.of(13,15)).doesNotContain(LocalTime.NOON, LocalTime.of(12,30), LocalTime.of(13,0));
        assertThat(availability.calendar(workshop,date.plusDays(1)).slots()).hasSize(4);
        assertThat(availability.calendar(workshop,date.plusDays(2)).slots()).isEmpty();
        var a = appointments.create(musician,workshop.getId(),form(day().slots().getFirst().start()));
        appointments.resolve(owner,a.getId(),a.getVersion(),"confirm");
        hours.put(dayName + "-opens", "09:00"); availability.saveHours(owner,version(),List.of(dayName,tomorrow),hours);
        assertThat(a.getProposedLocal().toLocalTime()).isEqualTo(LocalTime.of(8,0));
        hours.put(dayName + "-breakEnd", "");
        assertThatThrownBy(() -> availability.saveHours(owner,version(),List.of(dayName),hours)).isInstanceOf(IllegalArgumentException.class);
        assertThat(day().settings().hours(date.getDayOfWeek()).getBreakEnd()).isEqualTo(LocalTime.of(13,15));
        mvc.perform(get("/workshop/availability").with(user(owner.getEmail())).param("lang","en"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Hours and breaks by weekday")));
    }
    @Test void weekAndMonthOverviewsArePrivateAndShowBookingsAndBlockedDays() throws Exception {
        configure(30); var a = appointments.create(musician,workshop.getId(),form(day().slots().getFirst().start()));
        appointments.resolve(owner,a.getId(),a.getVersion(),"confirm"); availability.block(owner,version(),date.plusDays(1));
        var week = availability.overview(owner,date,"week"); assertThat(week).hasSize(7);
        assertThat(week.getFirst().date().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(availability.overview(owner,date,"month")).hasSize(date.lengthOfMonth());
        assertThat(week.stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow().bookings()).contains(a);
        for (String view : List.of("week", "month")) {
            mvc.perform(get("/workshop/availability/calendar").with(user(owner.getEmail())).param("date",date.toString()).param("view",view).param("lang","en"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(content().string(containsString(a.getTitle()))).andExpect(content().string(not(containsString("??"))));
            mvc.perform(get("/workshop/availability/calendar").with(user(other.getEmail())).param("view",view)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/workshop/availability/calendar").with(user(owner.getEmail())).param("view","invalid")).andExpect(status().isBadRequest());
    }
    private void configure(int minutes) { availability.save(owner,-1,true,"UTC",minutes,"09:00","12:00",allDays); }
    private AvailabilityService.CalendarDay day() { return availability.calendar(workshop,date); }
    private long version() { return settings.findById(workshop.getId()).orElseThrow().getVersion(); }
    private Map<String,String> form(Instant time) { return Map.of("title","Calendar visit","message","Please inspect my guitar","slot",time.toString(),"availabilityVersion",Long.toString(version()),"timeZone","Moon/Forged"); }
    private User account(String name) { var u=new User(); u.setUsername(name); u.setEmail(name+"@example.org"); u.setPasswordHash("unused"); return users.saveAndFlush(u); }
}
