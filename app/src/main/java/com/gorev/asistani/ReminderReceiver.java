package com.gorev.asistani;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Scheduler.notifyNow(context,
                intent.getIntExtra("id", 1),
                intent.getStringExtra("title"),
                intent.getStringExtra("body"));
    }
}
