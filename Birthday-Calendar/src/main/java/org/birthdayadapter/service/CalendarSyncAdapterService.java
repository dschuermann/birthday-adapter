/*
 * Copyright (C) 2012-2013 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010 Sam Steele
 *
 * This file is part of Birthday Adapter.
 * 
 * Birthday Adapter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Birthday Adapter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Birthday Adapter.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.birthdayadapter.service;

import android.accounts.Account;
import android.accounts.OperationCanceledException;
import android.annotation.SuppressLint;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.BaseColumns;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Reminders;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;

import org.birthdayadapter.BuildConfig;
import org.birthdayadapter.R;
import org.birthdayadapter.provider.ProviderHelper;
import org.birthdayadapter.util.AccountHelper;
import org.birthdayadapter.util.Constants;
import org.birthdayadapter.util.Log;
import org.birthdayadapter.util.PreferencesHelper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.TimeZone;

@SuppressLint("NewApi")
public class CalendarSyncAdapterService extends Service {

    private static String CALENDAR_COLUMN_NAME = "birthday_adapter";

    public CalendarSyncAdapterService() {
        super();
    }

    private class CalendarSyncAdapter extends AbstractThreadedSyncAdapter {

        CalendarSyncAdapter() {
            super(CalendarSyncAdapterService.this, true);
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority,
                                  ContentProviderClient provider, SyncResult syncResult) {
            try {
                CalendarSyncAdapterService.performSync(CalendarSyncAdapterService.this, account, extras, authority,
                        provider, syncResult);
            } catch (OperationCanceledException e) {
                Log.e(Constants.TAG, "OperationCanceledException", e);
            }
        }

        @Override
        public void onSecurityException(Account account, Bundle extras, String authority, SyncResult syncResult) {
            super.onSecurityException(account, extras, authority, syncResult);

            // contact or calendar permission has been revoked -> simply remove account
            AccountHelper accountHelper = new AccountHelper(CalendarSyncAdapterService.this, null);
            accountHelper.removeAccount();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new CalendarSyncAdapter().getSyncAdapterBinder();
    }

    /**
     * Builds URI for Birthday Adapter based on account. Ensures that only the calendar of Birthday
     * Adapter is chosen.
     */
    public static Uri getBirthdayAdapterUri(Uri uri) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, Constants.ACCOUNT_NAME)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE).build();
    }

    /**
     * Updates calendar color
     */
    public static void updateCalendarColor(Context context) {
        int color = PreferencesHelper.getColor(context);
        ContentResolver contentResolver = context.getContentResolver();

        Uri uri = ContentUris.withAppendedId(getBirthdayAdapterUri(Calendars.CONTENT_URI),
                getCalendar(context));

        Log.d(Constants.TAG, "Updating calendar color to " + color + " with uri " + uri.toString());

        ContentProviderClient client = contentResolver
                .acquireContentProviderClient(CalendarContract.AUTHORITY);

        ContentValues values = new ContentValues();
        values.put(Calendars.CALENDAR_COLOR, color);
        try {
            client.update(uri, values, null, null);
        } catch (RemoteException e) {
            Log.e(Constants.TAG, "Error while updating calendar color!", e);
        }
        client.release();
    }

    /**
     * Gets calendar id, when no calendar is present, create one!
     */
    private static long getCalendar(Context context) {
        Log.d(Constants.TAG, "getCalendar Method...");

        ContentResolver contentResolver = context.getContentResolver();

        // Find the calendar if we've got one
        Uri calenderUri = getBirthdayAdapterUri(Calendars.CONTENT_URI);

        // be sure to select the birthday calendar only (additionally to appendQueries in
        // getBirthdayAdapterUri for Android < 4)
        Cursor cursor = contentResolver.query(calenderUri, new String[]{BaseColumns._ID},
                Calendars.ACCOUNT_NAME + " = ? AND " + Calendars.ACCOUNT_TYPE + " = ?",
                new String[]{Constants.ACCOUNT_NAME, Constants.ACCOUNT_TYPE}, null);

        try {
            if (cursor != null && cursor.moveToNext()) {
                return cursor.getLong(0);
            } else {
                ArrayList<ContentProviderOperation> operationList = new ArrayList<>();

                ContentProviderOperation.Builder builder = ContentProviderOperation
                        .newInsert(calenderUri);
                builder.withValue(Calendars.ACCOUNT_NAME, Constants.ACCOUNT_NAME);
                builder.withValue(Calendars.ACCOUNT_TYPE, Constants.ACCOUNT_TYPE);
                builder.withValue(Calendars.NAME, CALENDAR_COLUMN_NAME);
                builder.withValue(Calendars.CALENDAR_DISPLAY_NAME,
                        context.getString(R.string.calendar_display_name));
                builder.withValue(Calendars.CALENDAR_COLOR, PreferencesHelper.getColor(context));
                if (BuildConfig.DEBUG) {
                    builder.withValue(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_EDITOR);
                } else {
                    builder.withValue(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ);
                }
                builder.withValue(Calendars.OWNER_ACCOUNT, Constants.ACCOUNT_NAME);
                builder.withValue(Calendars.SYNC_EVENTS, 1);
                builder.withValue(Calendars.VISIBLE, 1);
                operationList.add(builder.build());
                try {
                    contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                } catch (Exception e) {
                    Log.e(Constants.TAG, "getCalendar() failed", e);
                    return -1;
                }
                return getCalendar(context);
            }
        } finally {
            if (cursor != null && !cursor.isClosed())
                cursor.close();
        }
    }

    /**
     * Get a new ContentProviderOperation to insert a event
     */
    private static ContentProviderOperation insertEvent(Context context, long calendarId,
                                                        Date eventDate, int year, String title, String lookupKey) {
        ContentProviderOperation.Builder builder;

        builder = ContentProviderOperation.newInsert(getBirthdayAdapterUri(Events.CONTENT_URI));

        Calendar cal = Calendar.getInstance();
        cal.setTime(eventDate);
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        /*
         * Allday events have to be set in UTC!
         * 
         * Without UTC it results in: CalendarProvider2 W insertInTransaction: allDay is true but
         * sec, min, hour were not 0.
         * http://stackoverflow.com/questions/3440172/getting-exception-when
         * -inserting-events-in-android-calendar
         */
        cal.setTimeZone(TimeZone.getTimeZone("UTC"));
        // cal.setTimeZone(TimeZone.getTimeZone(Time.getCurrentTimezone()));

        /*
         * Define over entire day.
         * 
         * Note: ALL_DAY is enough on original Android calendar, but some calendar apps (Business
         * Calendar) do not display the event if time between dtstart and dtend is 0
         */
        long dtstart = cal.getTimeInMillis();
        long dtend = dtstart + DateUtils.DAY_IN_MILLIS;

        builder.withValue(Events.CALENDAR_ID, calendarId);
        builder.withValue(Events.DTSTART, dtstart);
        builder.withValue(Events.DTEND, dtend);
        builder.withValue(Events.EVENT_TIMEZONE, "UTC"); // ALL_DAY events must be UTC
        // builder.withValue(Events.EVENT_TIMEZONE, Time.getCurrentTimezone());

        builder.withValue(Events.ALL_DAY, 1);
        builder.withValue(Events.TITLE, title);
        builder.withValue(Events.STATUS, Events.STATUS_CONFIRMED);

        /*
         * Enable reminders for this event
         * 
         * Note: Needs to be explicitly set on Android < 4 to enable reminders
         */
        builder.withValue(Events.HAS_ALARM, 1);

        /*
         * Set availability to free.
         * 
         * Note: HTC calendar (4.0.3 Android + HTC Sense 4.0) will show a conflict with other events
         * if availability is not set to free!
         */
        if (Build.VERSION.SDK_INT >= 14) {
            builder.withValue(Events.AVAILABILITY, Events.AVAILABILITY_FREE);
        }

        // add button to open contact
        if (Build.VERSION.SDK_INT >= 16 && lookupKey != null) {
            builder.withValue(Events.CUSTOM_APP_PACKAGE, context.getPackageName());
            Uri contactLookupUri = Uri.withAppendedPath(
                    ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey);
            builder.withValue(Events.CUSTOM_APP_URI, contactLookupUri.toString());
        }

        return builder.build();
    }

    /**
     * Try to parse input with SimpleDateFormat
     *
     * @param format      SimpleDateFormat
     * @param setYear1700 When true the age will be not displayed in brackets
     * @return Date object if successful, otherwise null
     */
    private static Date parseStringWithSimpleDateFormat(String input, String format,
                                                        boolean setYear1700) {
        Log.d(Constants.TAG, "Trying to parse Event Date String " + input + " with " + format);
        SimpleDateFormat dateFormat = new SimpleDateFormat(format, Locale.US);
        dateFormat.setTimeZone(TimeZone.getDefault());
        try {
            Date parsedDate = dateFormat.parse(input);

            /*
             * Because no year is defined in address book, set year to 1700
             * 
             * When year < 1800, the age will be not displayed in brackets
             */
            if (setYear1700) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(parsedDate);
                cal.set(Calendar.YEAR, 1700);
                parsedDate = cal.getTime();
            }

            return parsedDate;
        } catch (ParseException e) {
            Log.d(Constants.TAG, "Parsing failed!");
            return null;
        }
    }

    /**
     * The date format in the contact events is not standardized! This method will try to parse it
     * trying different date formats.
     * <p/>
     * See also: http://dmfs.org/carddav/?date_format
     *
     * @return eventDate as Date object
     */
    private static Date parseEventDateString(Context context, String eventDateString) {
        if (eventDateString != null) {
            Date eventDate;

            // yyyy-MM-dd, Most used format!
            eventDate = parseStringWithSimpleDateFormat(eventDateString, "yyyy-MM-dd", false);

            // --MM-dd, Most used format without year!
            if (eventDate == null) {
                eventDate = parseStringWithSimpleDateFormat(eventDateString, "--MM-dd", true);
            }

            // yyyyMMdd, HTC Desire
            if (eventDate == null) {
                if (eventDateString.length() == 8) {
                    eventDate = parseStringWithSimpleDateFormat(eventDateString, "yyyyMMdd", false);
                }
            }

            // Unix timestamp, Some Motorola devices
            if (eventDate == null) {
                Log.d(Constants.TAG, "Trying to parse Event Date String " + eventDateString
                        + " as a unix timestamp!");
                try {
                    eventDate = new Date(Long.parseLong(eventDateString));
                } catch (NumberFormatException e) {
                    Log.d(Constants.TAG, "Parsing failed!");
                }
            }

            // dd.MM.yyyy
            if (eventDate == null) {
                eventDate = parseStringWithSimpleDateFormat(eventDateString, "dd.MM.yyyy", false);
            }

            // yyyy.MM.dd
            if (eventDate == null) {
                eventDate = parseStringWithSimpleDateFormat(eventDateString, "yyyy.MM.dd", false);
            }

            /**
             * Prefer dd/MM/yyyy over MM/dd/yyyy ?
             */
            if (PreferencesHelper.getPreferddSlashMM(context)) {
                // dd/MM/yyyy
                if (eventDate == null) {
                    eventDate = parseStringWithSimpleDateFormat(eventDateString, "dd/MM/yyyy",
                            false);
                }

                // dd/MM
                if (eventDate == null) {
                    eventDate = parseStringWithSimpleDateFormat(eventDateString, "dd/MM", true);
                }
            } else {
                // MM/dd/yyyy, Used by Facebook
                if (eventDate == null) {
                    eventDate = parseStringWithSimpleDateFormat(eventDateString, "MM/dd/yyyy",
                            false);
                }

                //MM/dd, Used by Facebook
                if (eventDate == null) {
                    eventDate = parseStringWithSimpleDateFormat(eventDateString, "MM/dd", true);
                }
            }

            /* Return */
            if (eventDate != null) {
                Log.d(Constants.TAG, "Event Date String " + eventDateString + " was parsed as "
                        + eventDate.toString());

                return eventDate;
            } else {
                Log.e(Constants.TAG, "Event Date String " + eventDateString
                        + " could NOT be parsed! returning null!");

                return null;
            }
        } else {
            Log.d(Constants.TAG, "Event Date String is null!");

            return null;
        }
    }

    /**
     * Get Cursor of contacts with events, but only those from Accounts not in our blacklist!
     * <p/>
     * This is really complicated, because we can't query SQLite directly. We need to use the provided Content Provider
     * and query several times for different tables.
     *
     * @return Cursor over all contacts with events, where accounts are not blacklisted
     */
    private static Cursor getContactsEvents(Context context, ContentResolver contentResolver) {
        // 0. get blacklist of Account names from own provider
        HashSet<Account> blacklist = ProviderHelper.getAccountBlacklist(context);

        // HashSet of already added events using generated identifiers to check for duplicates before adding
        HashSet<String> addedEventsIdentifiers = new HashSet<>();

        /*
         * 1. Get all raw contacts with their corresponding Account name and type (only raw contacts get get Account
         * affiliation
         */
        Uri rawContactsUri = ContactsContract.RawContacts.CONTENT_URI;
        String[] rawContactsProjection = new String[]{
                ContactsContract.RawContacts._ID,
                ContactsContract.RawContacts.CONTACT_ID,
                ContactsContract.RawContacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,};
        Cursor rawContacts = contentResolver.query(rawContactsUri, rawContactsProjection, null, null, null);

        /*
         * 2. Go over all raw contacts and check if the Account is allowed.
         * If Account is allowed, get display name and lookup key and all events for this contact.
         * Build a new MatrixCursor out of this data that can be used.
         */
        String[] columns = new String[]{
                BaseColumns._ID,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.CommonDataKinds.Event.START_DATE,
                ContactsContract.CommonDataKinds.Event.TYPE,
                ContactsContract.CommonDataKinds.Event.LABEL
        };
        MatrixCursor mc = new MatrixCursor(columns);
        int mcIndex = 0;
        try {
            while (rawContacts != null && rawContacts.moveToNext()) {
                long rawId = rawContacts.getLong(rawContacts.getColumnIndex(ContactsContract.RawContacts._ID));
                String accType = rawContacts.getString(rawContacts.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE));
                String accName = rawContacts.getString(rawContacts.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME));

                /*
                 * 2a. Check if Account is allowed (not blacklisted)
                 */
                boolean addEvent = false;
                if (TextUtils.isEmpty(accType) || TextUtils.isEmpty(accName)) {
                    // Workaround: Simply add events without proper Account
                    addEvent = true;
                } else {
                    Account acc = new Account(accName, accType);

                    if (!blacklist.contains(acc)) {
                        addEvent = true;
                    }
                }

                if (addEvent) {
                    String displayName = null;
                    String lookupKey = null;
                    String startDate;
                    int type;
                    String label;

                    /*
                     * 2b. Get display name and lookup key from normal contact table
                     */
                    String[] displayProjection = new String[]{
                            ContactsContract.Data.RAW_CONTACT_ID,
                            ContactsContract.Data.DISPLAY_NAME,
                            ContactsContract.Data.LOOKUP_KEY,
                    };
                    String displayWhere = ContactsContract.Data.RAW_CONTACT_ID + "= ?";
                    String[] displaySelectionArgs = new String[]{
                            String.valueOf(rawId)
                    };
                    Cursor displayCursor = contentResolver.query(ContactsContract.Data.CONTENT_URI, displayProjection,
                            displayWhere, displaySelectionArgs, null);
                    try {
                        if (displayCursor != null && displayCursor.moveToFirst()) {
                            displayName = displayCursor.getString(displayCursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME));
                            lookupKey = displayCursor.getString(displayCursor.getColumnIndex(ContactsContract.Data.LOOKUP_KEY));
                        }
                    } finally {
                        if (displayCursor != null && !displayCursor.isClosed())
                            displayCursor.close();
                    }

                    /*
                     * 2c. Get all events for this raw contact.
                     * We don't get this information for the (merged) contact table, but from the raw contact.
                     * If we would query this infos from the contact table we would also get events that should have been filtered!
                     */
                    Uri thisRawContactUri = ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId);
                    Uri entityUri = Uri.withAppendedPath(thisRawContactUri, ContactsContract.RawContacts.Entity.CONTENT_DIRECTORY);
                    String[] eventsProjection = new String[]{
                            ContactsContract.RawContacts._ID,
                            ContactsContract.RawContacts.Entity.DATA_ID,
                            ContactsContract.CommonDataKinds.Event.START_DATE,
                            ContactsContract.CommonDataKinds.Event.TYPE,
                            ContactsContract.CommonDataKinds.Event.LABEL
                    };
                    String eventsWhere = ContactsContract.RawContacts.Entity.MIMETYPE + "= ? AND "
                            + ContactsContract.RawContacts.Entity.DATA_ID + " IS NOT NULL";
                    String[] eventsSelectionArgs = new String[]{
                            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
                    };
                    Cursor eventsCursor = contentResolver.query(entityUri, eventsProjection, eventsWhere,
                            eventsSelectionArgs, null);
                    try {
                        while (eventsCursor != null && eventsCursor.moveToNext()) {
                            startDate = eventsCursor.getString(eventsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE));
                            type = eventsCursor.getInt(eventsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE));
                            label = eventsCursor.getString(eventsCursor.getColumnIndex(ContactsContract.CommonDataKinds.Event.LABEL));

                            /*
                             * 2d. Add this information to our MatrixCursor if not already added previously.
                             *
                             * If two SyncAdapter Accounts have the same contact with duplicated events, the event will already be in
                             * the HashSet addedEventsIdentifiers.
                             *
                             * eventIdentifier does not include startDate, because the String formats of startDate differ between accounts.
                             */
                            String eventIdentifier = lookupKey + type + label;
                            if (addedEventsIdentifiers.contains(eventIdentifier)) {
                                Log.d(Constants.TAG, "Event was NOT added, duplicate! Identifier: " + eventIdentifier);
                            } else {
                                Log.d(Constants.TAG, "Event was added! Identifier " + eventIdentifier);
                                addedEventsIdentifiers.add(eventIdentifier);

                                mc.newRow().add(mcIndex).add(displayName).add(lookupKey).add(startDate).add(type).add(label);
                                mcIndex++;
                            }
                        }
                    } finally {
                        if (eventsCursor != null && !eventsCursor.isClosed())
                            eventsCursor.close();
                    }
                }
            }
        } finally {
            if (rawContacts != null && !rawContacts.isClosed())
                rawContacts.close();
        }

        if (BuildConfig.DEBUG)
            DatabaseUtils.dumpCursor(mc);

        return mc;
    }

    /**
     * Generates title for events
     */
    private static String generateTitle(Context context, int eventType, Cursor cursor,
                                        int eventCustomLabelColumn, boolean includeAge, String displayName, int age) {
        displayName = addJubileeIcon(displayName, age);
        String title = null;
        if (displayName != null) {
            switch (eventType) {
                case ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM:
                    String eventCustomLabel = cursor.getString(eventCustomLabelColumn);

                    if (eventCustomLabel != null) {
                        title = String.format(PreferencesHelper.getLabel(context,
                                ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM, includeAge),
                                displayName, eventCustomLabel, age);
                    } else {
                        title = String.format(PreferencesHelper.getLabel(context,
                                ContactsContract.CommonDataKinds.Event.TYPE_OTHER, includeAge),
                                displayName, age);
                    }
                    break;
                case ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY:
                    title = String.format(PreferencesHelper.getLabel(context,
                            ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY, includeAge),
                            displayName, age);
                    break;
                case ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY:
                    title = String.format(PreferencesHelper.getLabel(context,
                            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY, includeAge),
                            displayName, age);
                    break;
                default:
                    // also ContactsContract.CommonDataKinds.Event.TYPE_OTHER
                    title = String.format(PreferencesHelper.getLabel(context,
                            ContactsContract.CommonDataKinds.Event.TYPE_OTHER, includeAge),
                            displayName, age);
                    break;
            }
        }

        return title;
    }

    /**
     * Adds an icon if jubelee age
     */
    private static String addJubileeIcon(String displayName, int age) {
        String jubilees = "18, 20, 30, 40, 50, 60, 70, 75, 80, 90, 100";
        boolean is_jubilee = jubilees.contains(String.valueOf(age));
        if (is_jubilee) {
            displayName = "🍾 " + displayName;
        }
        return displayName;
    }

    private static void cleanTables(ContentResolver contentResolver, long calendarId) {

        // empty table
        // with additional selection of calendar id, necessary on Android < 4 to remove events only
        // from birthday calendar
        int delEventsRows = contentResolver.delete(getBirthdayAdapterUri(Events.CONTENT_URI),
                Events.CALENDAR_ID + " = ?", new String[]{String.valueOf(calendarId)});
        Log.i(Constants.TAG, "Events of birthday calendar is now empty, deleted " + delEventsRows
                + " rows!");
        Log.i(Constants.TAG, "Reminders of birthday calendar is now empty!");
    }


    /**
     * Delete all reminders of birthday adapter by going through all events and delete corresponding
     * reminders. This is needed as ContentResolver can not join directly.
     * <p>
     * TODO: not used currently
     */
    private static void deleteAllReminders(Context context) {
        Log.d(Constants.TAG, "Going through all events and deleting all reminders...");

        ContentResolver contentResolver = context.getContentResolver();

        // get cursor for all events
        Cursor eventsCursor = contentResolver.query(getBirthdayAdapterUri(Events.CONTENT_URI),
                new String[]{Events._ID}, Events.CALENDAR_ID + "= ?",
                new String[]{String.valueOf(getCalendar(context))}, null);
        int eventIdColumn = eventsCursor.getColumnIndex(Events._ID);

        ArrayList<ContentProviderOperation> operationList = new ArrayList<ContentProviderOperation>();

        Uri remindersUri = getBirthdayAdapterUri(Reminders.CONTENT_URI);

        ContentProviderOperation.Builder builder = null;

        // go through all events
        try {
            while (eventsCursor.moveToNext()) {
                long eventId = eventsCursor.getLong(eventIdColumn);

                Log.d(Constants.TAG, "Delete reminders for event id: " + eventId);

                // get all reminders for this specific event
                Cursor remindersCursor = contentResolver.query(remindersUri, new String[]{
                                Reminders._ID, Reminders.MINUTES}, Reminders.EVENT_ID + "= ?",
                        new String[]{String.valueOf(eventId)}, null);
                int remindersIdColumn = remindersCursor.getColumnIndex(Reminders._ID);

                /* Delete reminders for this event */
                try {
                    while (remindersCursor.moveToNext()) {
                        long currentReminderId = remindersCursor.getLong(remindersIdColumn);
                        Uri currentReminderUri = ContentUris.withAppendedId(remindersUri,
                                currentReminderId);

                        builder = ContentProviderOperation.newDelete(currentReminderUri);

                        // add operation to list, later executed
                        if (builder != null) {
                            operationList.add(builder.build());
                        }
                    }
                } finally {
                    remindersCursor.close();
                }
            }
        } finally {
            eventsCursor.close();
        }

        try {
            contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
        } catch (Exception e) {
            Log.e(Constants.TAG, "Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void performSync(Context context, Account account, Bundle extras,
                                    String authority, ContentProviderClient provider, SyncResult syncResult)
            throws OperationCanceledException {
        performSync(context);
    }

    public static void performSync(Context context) {
        Log.d(Constants.TAG, "Starting sync...");

        ContentResolver contentResolver = context.getContentResolver();

        if (contentResolver == null) {
            Log.e(Constants.TAG, "Unable to get content resolver!");
            return;
        }

        long calendarId = getCalendar(context);
        if (calendarId == -1) {
            Log.e("CalendarSyncAdapter", "Unable to create calendar");
            return;
        }

        // Sync flow:
        // 1. Clear events table for this account completely
        cleanTables(contentResolver, calendarId);
        // 2. Get birthdays from contacts
        // 3. Create events and reminders for each birthday

        int[] reminderMinutes = PreferencesHelper.getAllReminderMinutes(context);

        // collection of birthdays that will later be added to the calendar
        ArrayList<ContentProviderOperation> operationList = new ArrayList<>();

        // iterate through all Contact Events
        Cursor cursor = getContactsEvents(context, contentResolver);

        if (cursor == null) {
            Log.e(Constants.TAG, "Unable to get events from contacts! Cursor returns null!");
            return;
        }

        try {
            int eventDateColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.START_DATE);
            int displayNameColumn = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            int eventTypeColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.TYPE);
            int eventCustomLabelColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.LABEL);
            int eventLookupKeyColumn = cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Event.LOOKUP_KEY);

            int backRef = 0;
            // for every event...
            while (cursor.moveToNext()) {
                String eventDateString = cursor.getString(eventDateColumn);
                String displayName = cursor.getString(displayNameColumn);
                int eventType = cursor.getInt(eventTypeColumn);
                String eventLookupKey = cursor.getString(eventLookupKeyColumn);

                Date eventDate = parseEventDateString(context, eventDateString);

                // only proceed when parsing didn't fail
                if (eventDate != null) {

                    // get year from event
                    Calendar eventCal = Calendar.getInstance();
                    eventCal.setTime(eventDate);
                    int eventYear = eventCal.get(Calendar.YEAR);
                    Log.d(Constants.TAG, "Event Year: " + eventYear);

                    /*
                     * If year < 1800 don't show brackets with age behind name.
                     * 
                     * When no year is defined parseEventDateString() sets it to 1700
                     * 
                     * Also iCloud for example sets year to 1604 if no year is defined in their user
                     * interface
                     */
                    boolean hasYear = false;
                    if (eventYear >= 1800) {
                        hasYear = true;
                    }

                    // get current year
                    Calendar currCal = Calendar.getInstance();
                    int currYear = currCal.get(Calendar.YEAR);

                    /*
                     * Insert events for the past 3 years and the next 5 years.
                     * 
                     * Events are not inserted as recurring events to have different titles with
                     * birthday age in it.
                     */
                    int startYear = currYear - 3;
                    int endYear = currYear + 5;

                    for (int iteratedYear = startYear; iteratedYear <= endYear; iteratedYear++) {
                        Log.d(Constants.TAG, "iteratedYear: " + iteratedYear);

                        // calculate age
                        int age = iteratedYear - eventYear;

                        // if birthday has year and age of this event >= 0, display age in title
                        boolean includeAge = false;
                        if (hasYear && age >= 0) {
                            includeAge = true;
                        }

                        String title = generateTitle(context, eventType, cursor,
                                eventCustomLabelColumn, includeAge, displayName, age);

                        if (title != null) {
                            Log.d(Constants.TAG, "Title: " + title);
                            Log.d(Constants.TAG, "BackRef is " + backRef);

                            operationList.add(insertEvent(context, calendarId, eventDate,
                                    iteratedYear, title, eventLookupKey));

                            /*
                             * Gets ContentProviderOperation to insert new reminder to the
                             * ContentProviderOperation with the given backRef. This is done using
                             * "withValueBackReference"
                             */
                            int noOfReminderOperations = 0;
                            for (int i = 0; i < 3; i++) {
                                if (reminderMinutes[i] != Constants.DISABLED_REMINDER) {
                                    ContentProviderOperation.Builder builder = ContentProviderOperation
                                            .newInsert(getBirthdayAdapterUri(Reminders.CONTENT_URI));

                                    /*
                                     * add reminder to last added event identified by backRef
                                     *
                                     * see http://stackoverflow.com/questions/4655291/semantics-of-
                                     * withvaluebackreference
                                     */
                                    builder.withValueBackReference(Reminders.EVENT_ID, backRef);
                                    builder.withValue(Reminders.MINUTES, reminderMinutes[i]);
                                    builder.withValue(Reminders.METHOD, Reminders.METHOD_ALERT);
                                    operationList.add(builder.build());

                                    noOfReminderOperations += 1;
                                }
                            }

                            // back references for the next reminders, 1 is for the event
                            backRef += 1 + noOfReminderOperations;
                        } else {
                            Log.d(Constants.TAG, "Title is null -> Not inserting events and reminders!");
                        }

                        /*
                         * intermediate commit - otherwise the binder transaction fails on large
                         * operationList
                         */
                        if (operationList.size() > 200) {
                            try {
                                Log.d(Constants.TAG, "Start applying the batch...");
                                contentResolver.applyBatch(CalendarContract.AUTHORITY,
                                        operationList);
                                Log.d(Constants.TAG, "Applying the batch was successful!");
                                backRef = 0;
                                operationList.clear();
                            } catch (Exception e) {
                                Log.e(Constants.TAG, "Applying batch error!", e);
                            }
                        }
                    }
                }
            }
        } finally {
            if (!cursor.isClosed())
                cursor.close();
        }

        /* Create events */
        if (operationList.size() > 0) {
            try {
                Log.d(Constants.TAG, "Start applying the batch...");
                contentResolver.applyBatch(CalendarContract.AUTHORITY, operationList);
                Log.d(Constants.TAG, "Applying the batch was successful!");
            } catch (Exception e) {
                Log.e(Constants.TAG, "Applying batch error!", e);
            }
        }
    }
}
