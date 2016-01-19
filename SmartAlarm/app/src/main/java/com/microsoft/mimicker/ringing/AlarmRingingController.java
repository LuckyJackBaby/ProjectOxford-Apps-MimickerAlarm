package com.microsoft.mimicker.ringing;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.microsoft.mimicker.model.Alarm;
import com.microsoft.mimicker.model.AlarmList;
import com.microsoft.mimicker.scheduling.AlarmNotificationManager;
import com.microsoft.mimicker.scheduling.AlarmScheduler;
import com.microsoft.mimicker.utilities.SharedWakeLock;

import java.util.UUID;

/**
 * This class is hosted by the AlarmRingingService. It controls the visibility of the alarm
 * ringing user experience and the playing of the alarm ringtone and vibration.
 *
 * This class derives from the AlarmRingingSessionDispatcher which is an abstract class that
 * handles the serialized queueing logic for the ringing of alarms. The controller implements the
 * abstract methods which are called when the first item enters the queue (for resource
 * initialization, when each item is dispatched and when the queue is drained (for resource
 * cleanup).
 *
 * This alarm ringing user experience (AlarmRingingActivity) calls back into this class via bound
 * calls to the AlarmRingingService to notify when:
 *
 *  We should start and stop playing the alarm ringtone and vibrate the device
 *  The user experience was completed using by finishing a game correctly
 *  The user experience was dismissed without finishing a game correctly e.g. by pressing the
 *  home button. In this case the alarm intent is resent to ensure the ringing experience is
 *  reshown to the user
 *  The user experience wants to be dismissed but in a scenario where another activity or task is
 *  launched i.e. the share flow
 */
public final class AlarmRingingController extends AlarmRingingSessionDispatcher {
    private Context mContext;
    private AlarmRingtonePlayer mRingtonePlayer;
    private AlarmVibrator mVibrator;
    private Alarm mCurrentAlarm;
    private boolean mAllowDismissRequested;

    public AlarmRingingController(Context context) {
        mContext = context;
        mRingtonePlayer = new AlarmRingtonePlayer(mContext);
        mVibrator = new AlarmVibrator(mContext);
    }

    public static AlarmRingingController newInstance(Context context) {
        return new AlarmRingingController(context);
    }

    @Override
    public void beforeDispatchFirstAlarmRingingSession() {
        mRingtonePlayer.initialize();
        mVibrator.initialize();
        SharedWakeLock.get(mContext).acquireFullWakeLock();
    }

    @Override
    protected void alarmRingingSessionCompleted() {
        // We need to handle the case where the alarm timed out. In that case we
        // wont get an explicit call from the AlarmRingingActivity to silence the alarm
        silenceAlarmRinging();
        mCurrentAlarm = null;
        super.alarmRingingSessionCompleted();
    }

    @Override
    public void allAlarmRingingSessionsComplete() {
        // Cleanup the state now that we are done with all ringing sessions
        mVibrator.cleanup();
        mRingtonePlayer.cleanup();

        SharedWakeLock.get(mContext).releaseFullWakeLock();
        // We should now update the notification to show the next alarm if appropriate
        AlarmNotificationManager.get(mContext).handleNextAlarmNotificationStatus();
    }

    @Override
    public void dispatchAlarmRingingSession(Intent intent) {
        if (intent != null) {
            UUID alarmId = (UUID) intent.getExtras().getSerializable(AlarmScheduler.ARGS_ALARM_ID);
            mCurrentAlarm = AlarmList.get(mContext).getAlarm(alarmId);
            startAlarmRinging();
            launchRingingUserExperience(alarmId);
            AlarmNotificationManager.get(mContext).handleAlarmRunningNotificationStatus(alarmId);
        }
    }

    public void silenceAlarmRinging() {
        mVibrator.stop();
        mRingtonePlayer.stop();
    }

    public void startAlarmRinging() {
        if (mCurrentAlarm.shouldVibrate()) {
            mVibrator.vibrate();
        }
        Uri ringtone = mCurrentAlarm.getAlarmTone();
        if (ringtone != null) {
            mRingtonePlayer.play(ringtone);
        }
    }

    public void requestAllowDismiss() {
        mAllowDismissRequested = true;
    }

    // alarmRingingSessionCompleted should always be called before this method.  If not, we should
    // restart the AlarmRingingActivity so that we can successfully finish the alarm session
    public void alarmRingingSessionDismissed() {
        if (mAllowDismissRequested) {
            mAllowDismissRequested = false;
        } else {
            if (mCurrentAlarm != null) {
                launchRingingUserExperience(mCurrentAlarm.getId());
            }
        }
    }

    private void launchRingingUserExperience(UUID alarmId) {
        Intent ringingIntent = new Intent(mContext, AlarmRingingActivity.class);
        ringingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ringingIntent.putExtra(AlarmRingingService.ALARM_ID, alarmId);
        mContext.startActivity(ringingIntent);
    }
}
