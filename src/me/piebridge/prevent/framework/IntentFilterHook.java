package me.piebridge.prevent.framework;

import android.app.AppGlobals;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

import java.util.Map;

import me.piebridge.prevent.common.GmsUtils;
import me.piebridge.prevent.framework.util.AlarmManagerServiceUtils;
import me.piebridge.prevent.framework.util.BroadcastFilterUtils;
import me.piebridge.prevent.framework.util.LogUtils;
import me.piebridge.prevent.framework.util.NotificationManagerServiceUtils;
import me.piebridge.prevent.xposed.XposedMod;

/**
 * Created by thom on 15/8/11.
 */
public class IntentFilterHook {

    private static Context mContext;
    private static AccountWatcher accountWatcher;
    private static Map<String, Boolean> mPreventPackages;

    private IntentFilterHook() {

    }

    public static void setContext(Context context, Map<String, Boolean> preventPackages) {
        mPreventPackages = preventPackages;
        accountWatcher = new AccountWatcher(context);
        mContext = context;
    }

    public static boolean canHook(int result) {
        return result > 0 && SystemHook.isSystemHook() && mContext != null;
    }

    public static IntentFilterMatchResult hookAfterMatch(Object filter, Object[] args) {
        String action = (String) args[0];
        if (filter instanceof PackageParser.ActivityIntentInfo) {
            return hookActivityIntentInfo((PackageParser.ActivityIntentInfo) filter, XposedMod.RECEIVER_SENDER.get(), action);
        } else if (filter instanceof PackageParser.ServiceIntentInfo) {
            return hookServiceIntentInfo((PackageParser.ServiceIntentInfo) filter, XposedMod.SERVICE_SENDER.get(), action);
        } else if (BroadcastFilterUtils.isBroadcastFilter(filter)) {
            return hookBroadcastFilter(filter, args);
        }

        return IntentFilterMatchResult.NONE;
    }

    private static IntentFilterMatchResult hookBroadcastFilter(Object filter, Object[] args) {
        String action = (String) args[0];
        if (NotificationManagerServiceUtils.canHook(filter, action)) {
            return NotificationManagerServiceUtils.hook((Uri) args[0x3], mPreventPackages);
        } else if (AlarmManagerServiceUtils.canHook(args)) {
            return AlarmManagerServiceUtils.hook(filter);
        } else if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
            return hookCloseSystemDialogs(filter, action);
        }
        return IntentFilterMatchResult.NONE;
    }

    private static IntentFilterMatchResult hookCloseSystemDialogs(Object filter, String action) {
        String packageName = BroadcastFilterUtils.getPackageName(filter);
        if (packageName != null && mPreventPackages.containsKey(packageName)) {
            LogUtils.logIntentFilter(true, "(ignore)", filter, action, packageName);
            return IntentFilterMatchResult.NO_MATCH;
        }
        return IntentFilterMatchResult.NONE;
    }

    private static IntentFilterMatchResult allowSafeIntent(PackageParser.ActivityIntentInfo filter, String sender, String action, String packageName) {
        LogUtils.logIntentFilterWarning(false, sender, filter, action, packageName);
        if (Boolean.TRUE.equals(mPreventPackages.get(packageName))) {
            PreventLog.w("allow " + packageName + " for next service/broadcast");
            mPreventPackages.put(packageName, false);
            SystemHook.restoreLater(packageName);
        }
        return IntentFilterMatchResult.NONE;
    }

    private static boolean isSystemSender(String sender) {
        return (sender == null || "android".equals(sender)) && Binder.getCallingUid() == Process.SYSTEM_UID;
    }

    private static boolean cannotPrevent(String packageName, String sender) {
        return !Boolean.TRUE.equals(mPreventPackages.get(packageName)) || packageName.equals(sender);
    }

    private static boolean cannotPreventGms(String packageName, String sender) {
        return GmsUtils.isGms(packageName) && (GmsUtils.isGapps(mContext.getPackageManager(), sender) || GmsUtils.isGappsCaller(mContext));
    }

    private static boolean isSafeReceiverAction(boolean isSystem, String action) {
        return isSystem && !AppGlobals.getPackageManager().isProtectedBroadcast(action);
    }

    private static boolean isSafeServiceAction(String action, String packageName) {
        return accountWatcher.cannotHook(action, packageName) || GmsUtils.isGcmRegisterAction(action);
    }

    private static IntentFilterMatchResult hookActivityIntentInfo(PackageParser.ActivityIntentInfo filter, String sender, String action) {
        // for receiver, we don't block for activity
        PackageParser.Activity activity = filter.activity;
        PackageParser.Package owner = activity.owner;
        if (owner.receivers.contains(activity)) {
            return hookReceiver(filter, owner.applicationInfo, sender, action);
        } else {
            // we only care about receiver
            return IntentFilterMatchResult.NONE;
        }
    }

    private static IntentFilterMatchResult hookReceiver(PackageParser.ActivityIntentInfo filter, ApplicationInfo ai, String sender, String action) {
        String packageName = ai.packageName;
        if (cannotPrevent(packageName, sender)) {
            LogUtils.logIntentFilter(false, sender, filter, action, packageName);
            return IntentFilterMatchResult.NONE;
        }
        boolean isSystem = isSystemSender(sender);
        if (cannotPreventGms(packageName, sender)) {
            LogUtils.logIntentFilter(false, sender, filter, action, packageName);
            return IntentFilterMatchResult.NONE;
        } else if (GmsUtils.isGcmAction(sender, isSystem, action)) {
            LogUtils.logIntentFilterWarning(false, sender, filter, action, packageName);
            return allowSafeIntent(filter, sender, action, packageName);
        } else if (isSafeReceiverAction(isSystem, action)) {
            LogUtils.logIntentFilterWarning(false, sender, filter, action, packageName);
            return IntentFilterMatchResult.NONE;
        }
        LogUtils.logIntentFilter(true, sender, filter, action, packageName);
        return IntentFilterMatchResult.NO_MATCH;
    }

    private static IntentFilterMatchResult hookServiceIntentInfo(PackageParser.ServiceIntentInfo filter, String sender, String action) {
        PackageParser.Service service = filter.service;
        PackageParser.Package owner = service.owner;
        ApplicationInfo ai = owner.applicationInfo;
        String packageName = ai.packageName;
        if (cannotPrevent(packageName, sender)) {
            LogUtils.logIntentFilter(false, sender, filter, action, packageName);
            return IntentFilterMatchResult.NONE;
        }
        if (isSafeServiceAction(action, packageName)) {
            LogUtils.logIntentFilterWarning(false, sender, filter, action, ai.packageName);
            return IntentFilterMatchResult.NONE;
        } else if (cannotPreventGms(packageName, sender)) {
            LogUtils.logIntentFilter(false, sender, filter, action, ai.packageName);
            return IntentFilterMatchResult.NONE;
        } else if (!isSystemSender(sender)) {
            LogUtils.logIntentFilterWarning(true, sender, filter, action, ai.packageName);
            return IntentFilterMatchResult.NO_MATCH;
        }
        LogUtils.logIntentFilter(false, sender, filter, action, packageName);
        return IntentFilterMatchResult.NONE;
    }

    public static void onPackageAdded() {
        if (accountWatcher != null) {
            accountWatcher.updateAuthDescriptions();
        }
    }

    public static boolean isPrevent(Intent intent) {
        String action = intent.getAction();
        return !AppGlobals.getPackageManager().isProtectedBroadcast(action) || GmsUtils.isGcmAction(null, true, action);
    }


}
