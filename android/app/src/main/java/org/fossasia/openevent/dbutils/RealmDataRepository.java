package org.fossasia.openevent.dbutils;

import org.fossasia.openevent.OpenEventApp;
import org.fossasia.openevent.data.Event;
import org.fossasia.openevent.data.Microlocation;
import org.fossasia.openevent.data.Session;
import org.fossasia.openevent.data.Speaker;
import org.fossasia.openevent.data.Sponsor;
import org.fossasia.openevent.data.Track;
import org.fossasia.openevent.data.extras.EventDates;
import org.fossasia.openevent.data.extras.Version;
import org.fossasia.openevent.events.BookmarkChangedEvent;
import org.fossasia.openevent.utils.ISO8601Date;

import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;

import io.reactivex.Completable;
import io.reactivex.schedulers.Schedulers;
import io.realm.Case;
import io.realm.Realm;
import io.realm.RealmList;
import io.realm.RealmResults;
import io.realm.Sort;
import timber.log.Timber;

public class RealmDataRepository {

    private Realm realm;

    private static RealmDataRepository realmDataRepository;

    private static HashMap<Realm, RealmDataRepository> repoCache = new HashMap<>();

    public static RealmDataRepository getDefaultInstance() {
        if(realmDataRepository == null)
            realmDataRepository = new RealmDataRepository(Realm.getDefaultInstance());

        return realmDataRepository;
    }

    /**
     * For threaded operation, a separate Realm instance is needed, not the default
     * instance, and thus all Realm objects can not pass through threads, extra care
     * must be taken to close the Realm instance after use or else app will crash
     * onDestroy of MainActivity. This is to ensure the database remains compact and
     * application remains free of silent bugs
     * @param realmInstance Separate Realm instance to be used
     * @return Realm Data Repository
     */
    public static RealmDataRepository getInstance(Realm realmInstance) {
        if(!repoCache.containsKey(realmInstance)) {
            repoCache.put(realmInstance, new RealmDataRepository(realmInstance));
        }
        return repoCache.get(realmInstance);
    }

    private RealmDataRepository(Realm realm) {
        this.realm = realm;
    }

    public Realm getRealmInstance() {
        return realm;
    }

    // Events Section

    private void saveEventInRealm(Event event) {
        realm.beginTransaction();
        realm.insertOrUpdate(event);
        realm.commitTransaction();
    }

    /**
     * Saves the Event object in database and returns Completable
     * object for tracking the state of operation
     * @param event Event which is to be stored
     * @return Completable object to be subscribed by caller
     */
    public Completable saveEvent(final Event event) {
        return Completable.fromAction(() -> {
            saveEventInRealm(event);
            Timber.d("Saved Event");
        });
    }

    /**
     * Returns Future style Event which is null
     * To get the contents of Event, add an OnRealmChangeListener
     * which notifies about the object state asynchronously
     * @return Event Returns Event Future
     */
    public Event getEvent() {
        return realm.where(Event.class).findFirstAsync();
    }

    /**
     * Returns Event synchronously
     * @return Event
     */
    public Event getEventSync() {
        return realm.where(Event.class).findFirst();
    }

    /**
     * Returns Future of the stored version IDs of event components
     * @return Version IDs of different Event Components
     */
    public Version getVersionIdsSync() {
        Realm realm = Realm.getDefaultInstance();

        return realm.where(Version.class).findFirst();
    }

    // Tracks Section

    /**
     * Saves tracks while merging with sessions asynchronously
     * @param tracks Tracks to be saved
     */
    private void saveTracksInRealm(final List<Track> tracks) {
        // Since this is a threaded operation. We need our own instance of Realm
        Realm realm = Realm.getDefaultInstance();

        realm.executeTransaction(realm1 -> {
            for(Track track : tracks) {
                List<Session> sessions = track.getSessions();

                RealmList<Session> newSessions = new RealmList<>();

                for (Session session : sessions) {
                    // To prevent overwriting of previously saved values
                    Session stored = realm1.where(Session.class).equalTo("id", session.getId()).findFirst();

                    if (stored != null) {
                        newSessions.add(stored);
                    } else {
                        newSessions.add(session);
                    }
                }

                track.setSessions(newSessions);

                realm1.insertOrUpdate(track);
            }
        });

        realm.close();
    }

    /**
     * Saves the list of Tracks in database and returns Completable
     * object for tracking the state of operation
     * @param tracks Tracks to be saved
     * @return Completable object to be subscribed by caller
     */
    public Completable saveTracks(final List<Track> tracks) {
        return Completable.fromAction(() -> {
            saveTracksInRealm(tracks);
            Timber.d("Saved Tracks");
        });
    }

    /**
     * Returns filtered tracks according to query
     * @param query Query String WITHOUT wildcards
     * @return List of Tracks following constraints
     */
    public RealmResults<Track> getTracksFiltered(String query) {
        String wildcardQuery = String.format("*%s*", query);

        return realm.where(Track.class)
                .like("name", wildcardQuery, Case.INSENSITIVE)
                .findAllSorted("name");
    }

    public RealmResults<Track> getTracks() {
        return realm.where(Track.class).findAllSortedAsync("name");
    }

    public RealmResults<Track> getTracksSync() {
        return realm.where(Track.class).findAllSorted("name");
    }

    public Track getTrack(int trackId) {
        return realm.where(Track.class).equalTo("id", trackId).findFirstAsync();
    }

    // Session Section

    /**
     * Saves sessions while merging with tracks and speakers asynchronously
     * @param sessions Sessions to be saved
     */
    private void saveSessionsInRealm(final List<Session> sessions) {
        // Since this is a threaded operation. We need our own instance of Realm
        Realm realm = Realm.getDefaultInstance();

        realm.executeTransaction(transaction -> {

            for(Session session : sessions) {
                // If session was previously bookmarked, set this one too
                Session storedSession = transaction.where(Session.class).equalTo("id", session.getId()).findFirst();
                if(storedSession != null && storedSession.isBookmarked())
                    session.setBookmarked(true);

                List<Speaker> speakers = session.getSpeakers();

                RealmList<Speaker> newSpeakers = new RealmList<>();

                for (Speaker speaker : speakers) {
                    // To prevent overwriting of previously saved values
                    Speaker stored = transaction.where(Speaker.class).equalTo("id", speaker.getId()).findFirst();

                    if (stored != null) {
                        newSpeakers.add(stored);
                    } else {
                        newSpeakers.add(speaker);
                    }
                }

                session.setSpeakers(newSpeakers);

                Track track = session.getTrack();

                // To prevent overwriting of previously saved values
                Track stored = transaction.where(Track.class).equalTo("id", track.getId()).findFirst();

                if(stored != null) {
                    session.setTrack(stored);
                }

                transaction.insertOrUpdate(session);
            }
        });

        realm.close();
    }

    public Completable saveSessions(final List<Session> sessions) {
        return Completable.fromAction(() -> {
            saveSessionsInRealm(sessions);
            Timber.d("Saved Sessions");
        });
    }

    /**
     * Sets bookmark of a session asynchronously
     * @param sessionId Session ID whose bookmark is to be updated
     * @param bookmark boolean value of bookmark to be set
     * @return Completable denoting action completion
     */
    public Completable setBookmark(final int sessionId, final boolean bookmark) {

        return Completable.fromAction(() -> {

            Realm realm1 = Realm.getDefaultInstance();

            realm1.beginTransaction();
            realm1.where(Session.class)
                    .equalTo("id", sessionId)
                    .findFirst()
                    .setBookmarked(bookmark);

            OpenEventApp.postEventOnUIThread(new BookmarkChangedEvent());
            realm1.commitTransaction();

            realm1.close();
        }).subscribeOn(Schedulers.io());
    }

    public Session getSession(int sessionId) {
        return realm.where(Session.class).equalTo("id", sessionId).findFirstAsync();
    }

    public Session getSessionSync(int sessionId) {
        return realm.where(Session.class).equalTo("id", sessionId).findFirst();
    }

    public Session getSession(String title) {
        return realm.where(Session.class).equalTo("title", title).findFirstAsync();
    }

    /**
     * Returns sessions belonging to a specific track filtered by
     * a query string.
     * @param trackId ID of Track which Sessions should belong to
     * @param query Query of search WITHOUT wildcards
     * @return List of Sessions following constraints
     */
    public RealmResults<Session> getSessionsFiltered(int trackId, String query) {
        String wildcardQuery = String.format("*%s*", query);

        return realm.where(Session.class)
                .equalTo("track.id", trackId)
                .like("title", wildcardQuery, Case.INSENSITIVE)
                .findAllSorted("startTime");
    }

    public RealmResults<Session> getSessionsByLocation(String location) {
        return realm.where(Session.class).equalTo("microlocation.name", location).findAllAsync();
    }

    public RealmResults<Session> getSessionsByDate(String date, String sortCriteria) {
        return realm.where(Session.class).equalTo("startDate", date).findAllSortedAsync(sortCriteria);
    }

    public RealmResults<Session> getSessionsByDateFiltered(String date, String query, String sortCriteria) {
        String wildcardQuery = String.format("*%s*", query);

        return realm.where(Session.class)
                .equalTo("startDate", date)
                .like("title", wildcardQuery, Case.INSENSITIVE)
                .findAllSorted(sortCriteria);
    }

    public RealmResults<Session> getBookMarkedSessions() {
        return realm.where(Session.class).equalTo("isBookmarked", true).findAllAsync();
    }

    public RealmResults<Session> getBookMarkedSessionsSync() {
        return realm.where(Session.class).equalTo("isBookmarked", true).findAll();
    }

    // Speakers Section

    /**
     * Saves speakers while merging with sessions asynchronously
     * @param speakers Speakers to be saved
     */
    private void saveSpeakersInRealm(final List<Speaker> speakers) {

        // Since this is a threaded operation. We need our own instance of Realm
        Realm realm = Realm.getDefaultInstance();

        realm.executeTransaction(transaction -> {
            for(Speaker speaker : speakers) {
                List<Session> sessions = speaker.getSessions();

                RealmList<Session> newSessions = new RealmList<>();

                for (Session session : sessions) {
                    // To prevent overwriting of previously saved values
                    Session stored = transaction.where(Session.class).equalTo("id", session.getId()).findFirst();

                    if (stored != null) {
                        newSessions.add(stored);
                    } else {
                        newSessions.add(session);
                    }
                }

                speaker.setSession(newSessions);

                transaction.insertOrUpdate(speaker);
            }
        });

        realm.close();
    }

    public Completable saveSpeakers(final List<Speaker> speakers) {
        return Completable.fromAction(() -> {
            saveSpeakersInRealm(speakers);
            Timber.d("Saved Speakers");
        });
    }

    public Speaker getSpeaker(String speakerName) {
        return realm.where(Speaker.class).equalTo("name", speakerName).findFirstAsync();
    }

    public RealmResults<Speaker> getSpeakers(String sortCriteria) {
        return realm.where(Speaker.class).findAllSortedAsync(sortCriteria);
    }

    public RealmResults<Speaker> getSpeakersSync(String sortCriteria) {
        return realm.where(Speaker.class).findAllSorted(sortCriteria);
    }

    public RealmResults<Speaker> getSpeakersFiltered(String query, String sortCriteria) {
        String wildcardQuery = String.format("*%s*", query);

        return realm.where(Speaker.class)
                .like("name", wildcardQuery, Case.INSENSITIVE)
                .findAllSorted(sortCriteria);
    }

    // Sponsors Section

    private void saveSponsorsInRealm(List<Sponsor> sponsors) {
        realm.beginTransaction();
        for(Sponsor sponsor : sponsors)
            realm.insertOrUpdate(sponsor);
        realm.commitTransaction();
    }

    public Completable saveSponsors(final List<Sponsor> sponsors) {
        return Completable.fromAction(() -> {
            saveSponsorsInRealm(sponsors);
            Timber.d("Saved Sponsors");
        });
    }

    public RealmResults<Sponsor> getSponsors() {
        return realm.where(Sponsor.class).findAllSortedAsync("level", Sort.DESCENDING, "name", Sort.ASCENDING);
    }

    // Location Section

    private void saveLocationsInRealm(List<Microlocation> locations) {
        realm.beginTransaction();
        for(Microlocation location : locations)
            realm.insertOrUpdate(location);
        realm.commitTransaction();
    }

    public Completable saveLocations(final List<Microlocation> locations) {
        return Completable.fromAction(() -> {
            saveLocationsInRealm(locations);
            Timber.d("Saved Locations");
        });
    }

    public RealmResults<Microlocation> getLocations() {
        return realm.where(Microlocation.class).findAllSortedAsync("name");
    }

    public RealmResults<Microlocation> getLocationsSync() {
        return realm.where(Microlocation.class).findAllSorted("name");
    }

    // Dates Section

    /**
     * Saves Event Dates Synchronously
     * TODO : Use threaded asynchronous transaction using separate Realm instance
     * @param start Starting Date of Event
     * @param end Ending Date of Event
     */
    private void saveEventDatesInRealm(Date start, Date end) {
        Calendar calendar = new GregorianCalendar();
        calendar.setTime(start);

        realm.beginTransaction();
        realm.delete(EventDates.class);
        while (calendar.getTime().before(end)) {
            Date result = calendar.getTime();
            Calendar cal = Calendar.getInstance();
            cal.setTime(result);
            realm.insertOrUpdate(new EventDates(ISO8601Date.dateFromCalendar(cal)));
            calendar.add(Calendar.DATE, 1);
        }
        realm.commitTransaction();
    }

    public Completable saveEventDates(final Date start, final Date end) {
        return Completable.fromAction(() -> saveEventDatesInRealm(start, end));
    }

    public RealmResults<EventDates> getEventDates() {
        return realm.where(EventDates.class).findAllAsync();
    }

    /**
     * Compacts the database to save space
     * Should be called when exiting application to ensure
     * all Realm instances are ready to be closed.
     *
     * Closing the repoCache instances is the responsibility
     * of caller
     */
    public static void compactDatabase() {
        Realm realm = realmDataRepository.getRealmInstance();

        Timber.d("Vacuuming the database");
        Realm.compactRealm(realm.getConfiguration());
    }

}
