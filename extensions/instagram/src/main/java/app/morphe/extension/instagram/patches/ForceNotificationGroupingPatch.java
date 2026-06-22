/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.instagram.patches;

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

@SuppressWarnings("unused")
public class ForceNotificationGroupingPatch {
    private static final String MODE_ALL = "all";
    private static final String MODE_CATEGORY = "category";
    private static final String GROUP_ALL = "morphe.instagram.notifications";
    private static final String GROUP_PREFIX = GROUP_ALL + ".";
    private static final String LOG_TAG = "MorpheIgNotifGroup";
    private static final String SUMMARY_TAG = "morphe.instagram.notification_group_summary";
    private static final String TEST_ACTION = "app.morphe.instagram.NOTIFICATION_GROUPING_TEST";
    private static final String TEST_CHANNEL_ID = "morphe_notification_grouping_test";
    private static final String TEST_TAG = "morphe.instagram.notification_grouping_test";
    private static final int SUMMARY_ID_BASE = 0x4d4f5247;
    private static final int TEST_ID_BASE = 0x4d4f5400;
    private static volatile Context applicationContext;
    private static volatile boolean testReceiverRegistered;

    /**
     * Patched at build time from the patch option.
     */
    private static String groupingMode() {
        return MODE_ALL;
    }

    /**
     * Patched at build time from the patch option.
     */
    private static boolean debugLogging() {
        return false;
    }

    /**
     * Called from Instagram's application bootstrap.
     */
    public static void initialize(Context context) {
        if (context == null) {
            return;
        }

        applicationContext = context.getApplicationContext();
        registerTestReceiver(applicationContext);
        debug("Initialized notification grouping context");
    }

    /**
     * Injection point.
     */
    public static Notification.Builder apply(Notification.Builder builder) {
        if (builder == null) {
            return null;
        }

        try {
            Notification notification = builder.build();
            String groupKey = groupKeyFor(notification);
            boolean isSummary = isGroupSummary(notification);

            builder.setGroup(groupKey);
            builder.setGroupSummary(isSummary);
            builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
            builder.setShortcutId(null);
            debug("Notification.Builder grouped as " + groupKey + ", summary=" + isSummary);
        } catch (Throwable ignored) {
            // Notification building must never be blocked by this cosmetic patch.
            debug("Notification.Builder grouping failed: " + ignored);
        }

        return builder;
    }

    /**
     * Injection point.
     */
    public static Object applyCompatBuilder(Object builder) {
        if (builder == null) {
            return null;
        }

        try {
            Method build = builder.getClass().getMethod("build");
            Object built = build.invoke(builder);

            if (!(built instanceof Notification)) {
                return builder;
            }

            String groupKey = groupKeyFor((Notification) built);
            boolean isSummary = isGroupSummary((Notification) built);
            invokeIfExists(builder, "setGroup", new Class<?>[]{String.class}, groupKey);
            invokeIfExists(builder, "setGroupSummary", new Class<?>[]{boolean.class}, isSummary);
            invokeIfExists(builder, "setGroupAlertBehavior", new Class<?>[]{int.class}, Notification.GROUP_ALERT_CHILDREN);
            invokeIfExists(builder, "setShortcutId", new Class<?>[]{String.class}, (Object) null);
            debug("NotificationCompat.Builder grouped as " + groupKey + ", summary=" + isSummary);
        } catch (Throwable ignored) {
            // Notification building must never be blocked by this cosmetic patch.
            debug("NotificationCompat.Builder grouping failed: " + ignored);
        }

        return builder;
    }

    /**
     * Injection point for already built notifications, right before they are posted.
     */
    public static Notification beforeNotify(Notification notification) {
        if (notification == null) {
            return null;
        }

        try {
            String groupKey = groupKeyFor(notification);
            boolean isSummary = isGroupSummary(notification);
            Notification groupedNotification = notification;

            Context context = applicationContext;
            if (Build.VERSION.SDK_INT >= 23 && context != null) {
                Builder builder = Builder.recoverBuilder(context, notification);
                builder.setGroup(groupKey);
                builder.setGroupSummary(isSummary);
                builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
                builder.setShortcutId(null);
                groupedNotification = builder.build();
            } else {
                debug("Skipping final rebuild for " + groupKey + ": missing context or unsupported Android version");
            }

            groupedNotification.flags |= Notification.FLAG_LOCAL_ONLY;
            groupedNotification.flags |= isSummary ? Notification.FLAG_GROUP_SUMMARY : 0;
            if (!isSummary) {
                groupedNotification.flags &= ~Notification.FLAG_GROUP_SUMMARY;
                ensureGroupSummary(groupKey, groupedNotification);
            }

            debug("NotificationManager.notify grouped as " + groupKey + ", summary=" + isSummary);
            return groupedNotification;
        } catch (Throwable ignored) {
            // Notification posting must never be blocked.
            debug("NotificationManager.notify grouping failed: " + ignored);
            return notification;
        }
    }

    private static void invokeIfExists(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            target.getClass().getMethod(methodName, parameterTypes).invoke(target, args);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isGroupSummary(Notification notification) {
        return (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private static void ensureGroupSummary(String groupKey, Notification child) {
        Context context = applicationContext;
        if (context == null) {
            debug("Skipping summary for " + groupKey + ": missing context");
            return;
        }

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            debug("Skipping summary for " + groupKey + ": missing notification manager");
            return;
        }

        try {
            Builder builder;
            if (Build.VERSION.SDK_INT >= 26 && child.getChannelId() != null) {
                builder = new Builder(context, child.getChannelId());
            } else {
                builder = new Builder(context);
            }

            if (Build.VERSION.SDK_INT >= 23 && child.getSmallIcon() != null) {
                builder.setSmallIcon(child.getSmallIcon());
            } else if (child.icon != 0) {
                builder.setSmallIcon(child.icon);
            }

            builder.setContentTitle("Instagram");
            builder.setContentText("Grouped notifications");
            builder.setShowWhen(false);
            builder.setLocalOnly(true);
            builder.setGroup(groupKey);
            builder.setGroupSummary(true);
            builder.setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN);
            builder.setColor(child.color);

            Notification summary = builder.build();
            summary.flags |= Notification.FLAG_GROUP_SUMMARY;
            summary.flags |= Notification.FLAG_LOCAL_ONLY;
            manager.notify(SUMMARY_TAG, summaryIdFor(groupKey), summary);
            debug("Posted summary for " + groupKey);
        } catch (Throwable error) {
            debug("Summary creation failed for " + groupKey + ": " + error);
        }
    }

    private static int summaryIdFor(String groupKey) {
        return SUMMARY_ID_BASE ^ groupKey.hashCode();
    }

    private static void registerTestReceiver(Context context) {
        if (!debugLogging() || testReceiverRegistered) {
            return;
        }

        try {
            IntentFilter filter = new IntentFilter(TEST_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(TEST_RECEIVER, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(TEST_RECEIVER, filter);
            }
            testReceiverRegistered = true;
            debug("Registered notification grouping test receiver: " + TEST_ACTION);
        } catch (Throwable error) {
            debug("Test receiver registration failed: " + error);
        }
    }

    private static final BroadcastReceiver TEST_RECEIVER = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !TEST_ACTION.equals(intent.getAction())) {
                return;
            }

            if (intent.getBooleanExtra("clear", false)) {
                clearTestNotifications(context);
                return;
            }

            int count = Math.max(1, Math.min(intent.getIntExtra("count", 5), 20));
            boolean grouped = intent.getBooleanExtra("grouped", true);
            postTestNotifications(context, count, grouped);
        }
    };

    private static void postTestNotifications(Context context, int count, boolean grouped) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            debug("Skipping test notifications: missing notification manager");
            return;
        }

        ensureTestChannel(manager);
        clearTestNotifications(context);

        for (int i = 0; i < count; i++) {
            try {
                Builder builder;
                if (Build.VERSION.SDK_INT >= 26) {
                    builder = new Builder(context, TEST_CHANNEL_ID);
                } else {
                    builder = new Builder(context);
                }

                builder.setSmallIcon(context.getApplicationInfo().icon);
                builder.setContentTitle("Morphe IG test " + (i + 1));
                builder.setContentText(i % 2 == 0 ? "Ti ha inviato un messaggio" : "Ha messo mi piace al tuo post");
                builder.setWhen(System.currentTimeMillis() + i);
                builder.setShowWhen(true);
                builder.setLocalOnly(true);
                builder.setAutoCancel(true);

                Notification notification = builder.build();
                if (grouped) {
                    notification = beforeNotify(notification);
                }

                manager.notify(TEST_TAG, TEST_ID_BASE + i, notification);
            } catch (Throwable error) {
                debug("Posting test notification " + i + " failed: " + error);
            }
        }

        debug("Posted " + count + " test notifications, grouped=" + grouped);
    }

    private static void clearTestNotifications(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }

        for (int i = 0; i < 20; i++) {
            manager.cancel(TEST_TAG, TEST_ID_BASE + i);
        }

        manager.cancel(SUMMARY_TAG, summaryIdFor(GROUP_ALL));
        manager.cancel(SUMMARY_TAG, summaryIdFor(GROUP_PREFIX + "messages"));
        manager.cancel(SUMMARY_TAG, summaryIdFor(GROUP_PREFIX + "interactions"));
        manager.cancel(SUMMARY_TAG, summaryIdFor(GROUP_PREFIX + "content"));
        manager.cancel(SUMMARY_TAG, summaryIdFor(GROUP_PREFIX + "account"));
        manager.cancel(SUMMARY_TAG, summaryIdFor(GROUP_PREFIX + "other"));
        debug("Cleared test notifications");
    }

    private static void ensureTestChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel existingChannel = manager.getNotificationChannel(TEST_CHANNEL_ID);
        if (existingChannel != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                TEST_CHANNEL_ID,
                "Morphe notification grouping test",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Temporary notifications used to test Morphe Instagram notification grouping.");
        manager.createNotificationChannel(channel);
    }

    private static String groupKeyFor(Notification notification) {
        if (MODE_CATEGORY.equals(groupingMode())) {
            String category = categoryFor(notification);
            debug("Detected category " + category + " for " + notificationText(notification));
            return GROUP_PREFIX + category;
        }

        return GROUP_ALL;
    }

    private static String categoryFor(Notification notification) {
        String source = notificationText(notification);

        if (containsAny(source,
                "direct", "message", "messag", "dm", "chat", "inbox",
                "messaggio", "messaggi")) {
            return "messages";
        }

        if (containsAny(source,
                "like", "liked", "comment", "commented", "mention", "mentioned", "tagged",
                "follow", "followed", "follower", "request", "reply", "replied",
                "piace", "commento", "commentato", "menzion", "taggat", "segui",
                "follower", "richiest", "rispost")) {
            return "interactions";
        }

        if (containsAny(source,
                "story", "stories", "reel", "post", "live", "broadcast",
                "storia", "storie", "diretta")) {
            return "content";
        }

        if (containsAny(source,
                "login", "security", "password", "account",
                "accesso", "sicurezza")) {
            return "account";
        }

        String category = notification.category;
        if (category != null && !category.isEmpty()) {
            return sanitizeCategory(category);
        }

        return "other";
    }

    private static String notificationText(Notification notification) {
        StringBuilder builder = new StringBuilder();

        append(builder, notification.getChannelId());
        append(builder, notification.category);

        Bundle extras = notification.extras;
        if (extras != null) {
            append(builder, extras.getCharSequence(Notification.EXTRA_TITLE));
            append(builder, extras.getCharSequence(Notification.EXTRA_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
        }

        return builder.toString().toLowerCase(Locale.US);
    }

    private static void append(StringBuilder builder, Object value) {
        if (value != null) {
            builder.append(' ').append(value);
        }
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }

        return false;
    }

    private static String sanitizeCategory(String category) {
        StringBuilder builder = new StringBuilder(category.length());
        String lowerCategory = category.toLowerCase(Locale.US);

        for (int i = 0; i < lowerCategory.length(); i++) {
            char c = lowerCategory.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                builder.append(c);
            }
        }

        return builder.length() == 0 ? "other" : builder.toString();
    }

    private static void debug(String message) {
        if (debugLogging()) {
            Log.d(LOG_TAG, message);
        }
    }
}
