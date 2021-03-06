package org.jembi.rad.mqttdemo.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.Uri;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jembi.rad.mqttdemo.R;
import org.jembi.rad.mqttdemo.RadMQTTDemoApplication;
import org.jembi.rad.mqttdemo.ui.SettingsActivity;
import org.jembi.rad.mqttdemo.ui.SubscribeActivity;
import org.jembi.rad.mqttdemo.model.Message;

import java.util.Date;
import java.util.UUID;

/**
 * MessageService is used to handle the MQTT communications. It manages the MQTTClient and the subscription.
 *
 * When the connection is made with the MQTT broker (or lost) and the subscription to the topic is successful,
 * an alert is sent via a LocalBroadcast, so that a popup can be displayed to the user. See the
 * BroadcastReceiver code in SubscribeActivity.
 *
 * When a message arrives on the topic, if notifications have been enabled in the settings and the app
 * is running in the background, a notification will be sent to the phone (with a customisable sound
 * and optional vibration. A LocalBroadcast is also sent, with the message, so that it can
 * be inserted into the message list and into the database. (See the BroadcastReceiver in SubscribeActivity.)
 *
 * FIXME: A future improvement to this Service would be to handle the exceptions that are thrown when
 * the mqttAndroidClient is connected to the broker and when the topic subscription is created. If
 * an exception is thrown at those two points, the messages will not work.
 */
public class MessageService extends Service {

    // App alert event constants
    public final static String EVENT_ALERT = "EVENT_ALERT";
    public final static String EVENT_ALERT_MESSAGE = "org.jembi.rad.mqttdemo.MESSAGE";

    // App online/offline event constants
    public final static String EVENT_BROKER_CONNECTION = "EVENT_BROKER_CONNECTION";
    public final static String EVENT_BROKER_CONNECTION_STATUS = "EVENT_BROKER_CONNECTION_STATUS";

    // App alert event constants
    public final static String EVENT_MESSAGE = "EVENT_MESSAGE";
    public final static String EVENT_MESSAGE_CONTENT = "org.jembi.rad.mqttdemo.MESSAGE_CONTENT";

    // Notification Alert constants
    private static long[] mVibratePattern = { 0, 200, 200, 300 };
    public static final int ALERT_NOTIFICATION_ID = 1;

    // MQTT constants
    private static int qos = 1;
    private static boolean cleanSession = false;
    private static boolean automaticReconnect = true;

    private MqttAndroidClient mqttAndroidClient;
    private String serverUri;
    private String clientId;
    private String topic;

    public MessageService() {}

    @Override
    public void onCreate() {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "Creating service to retrieve MQTT messages");

        // get connection details from preferences
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        serverUri =  preferences.getString(this.getString(R.string.server_uri_label), this.getString(R.string.server_uri));
        clientId =  preferences.getString(this.getString(R.string.client_id_label), null);
        topic = preferences.getString(this.getString(R.string.topic_label), this.getString(R.string.topic_name));

        if (clientId == null) {
            // set the clientId if it hasn't been set already (i.e. probably the first time using the application)
            clientId = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString(this.getString(R.string.client_id_label), clientId);
            editor.commit();
        }

        mqttAndroidClient = new MqttAndroidClient(getApplicationContext(), serverUri, clientId);
        mqttAndroidClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {

                updateBrokerConnectionStatus(true);

                if (reconnect) {
                    displayAlert("Reconnected to : " + serverURI);
                    if (cleanSession) {
                        subscribeToTopic();
                    }
                } else {
                    displayAlert("Connected to: " + serverURI);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                displayAlert("The Connection was lost.");
                updateBrokerConnectionStatus(false);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                // this callback is unnecessary because we will request a separate callback after subscription to a topic
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // we aren't sending messages, so no need to implement this callback method
            }
        });

        MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(automaticReconnect);
        mqttConnectOptions.setCleanSession(cleanSession);


        try {
            Log.i(RadMQTTDemoApplication.LOG_TAG, "Connecting to " + serverUri);
            IMqttToken token = mqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                    disconnectedBufferOptions.setBufferEnabled(true);
                    disconnectedBufferOptions.setBufferSize(100);
                    disconnectedBufferOptions.setPersistBuffer(false);
                    disconnectedBufferOptions.setDeleteOldestMessages(false);
                    mqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                    subscribeToTopic();
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(RadMQTTDemoApplication.LOG_TAG, "Failed to connect", exception);
                    displayAlert("Failed to connect to: " + serverUri);
                }
            });


        } catch (MqttException ex) {
            Log.e(RadMQTTDemoApplication.LOG_TAG, "Exception while connecting", ex);
        }

        Log.i(RadMQTTDemoApplication.LOG_TAG, "Finished creating service to retrieve MQTT messages");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "Checking status of service receiving MQTT messages");
        updateBrokerConnectionStatus(mqttAndroidClient.isConnected());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "I am dead to you ...");
    }

    private void subscribeToTopic() {
        try {
            mqttAndroidClient.subscribe(topic, qos, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    displayAlert("Subscribed to: " + topic);
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    displayAlert("Failed to subscribe to: " + topic);
                }
            });

            mqttAndroidClient.subscribe(topic, qos, new IMqttMessageListener() {
                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    // message Arrived!
                    String messageContent = new String(message.getPayload());
                    receiveMessage(new Message(new Date(), messageContent));
                }
            });

        } catch (MqttException ex){
            Log.e(RadMQTTDemoApplication.LOG_TAG, "Exception while subscribing", ex);
        }
    }

    private void receiveMessage(Message message) {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "Received an MQTT message: " + message.getMessage());

        if (!RadMQTTDemoApplication.getInstance().isAppInForeground()) {
            // if the app is paused or running in the background, then display a notification
            Context context = this.getApplicationContext();
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            boolean notificationEnabled = preferences.getBoolean(SettingsActivity.NOTIFICATION_NEW_MESSAGE_ENABLED, true);
            if (notificationEnabled) {
                // Define the Notification Intent
                Intent mainActivityIntent = new Intent(context, SubscribeActivity.class);
                PendingIntent mainActivityPendingIntent = PendingIntent.getActivity(context, 0,
                        mainActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT);

                // Build the notification
                Builder notificationBuilder = new Builder(context)
                        .setSmallIcon(R.drawable.ic_notifications_black_24dp)
                        .setContentTitle(context.getString(R.string.alert_notification_title))
                        .setSubText(context.getString(R.string.alert_notification_subtitle, topic))
                        .setTicker(context.getString(R.string.alert_notification_ticker))
                        .setStyle(new NotificationCompat.BigTextStyle().bigText(message.getMessage()))
                        .setNumber(1)
                        .setContentIntent(mainActivityPendingIntent)
                        .setAutoCancel(true);

                // set the ringtone
                String ringtone = preferences.getString(SettingsActivity.NOTIFICATION_NEW_MESSAGE_RINGTONE, null);
                if (ringtone != null) {
                    notificationBuilder.setSound(Uri.parse(ringtone));
                }

                // set the vibration
                boolean vibrate = preferences.getBoolean(SettingsActivity.NOTIFICATION_NEW_MESSAGE_VIBRATE, true);
                if (vibrate) {
                    notificationBuilder.setVibrate(mVibratePattern);
                }

                // Pass the Notification to the NotificationManager:
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                        .notify(ALERT_NOTIFICATION_ID, notificationBuilder.build());

                Log.i(RadMQTTDemoApplication.LOG_TAG, "Created a system notification: " + notificationBuilder.toString());
            }
        }

        // send the message to the SubscribeActivity to display to the user
        displayMessage(message);
    }

    private void displayAlert(String message) {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "Alert: " + message);
        Intent it = new Intent(EVENT_ALERT);
        if (!TextUtils.isEmpty(message)) {
            it.putExtra(EVENT_ALERT_MESSAGE, message);
        }
        LocalBroadcastManager.getInstance(this.getApplicationContext()).sendBroadcast(it);
    }

    private void displayMessage(Message message) {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "Message: " + message);
        Intent it = new Intent(EVENT_MESSAGE);
        it.putExtra(EVENT_MESSAGE_CONTENT, message);
        LocalBroadcastManager.getInstance(this.getApplicationContext()).sendBroadcast(it);
    }

    private void updateBrokerConnectionStatus(Boolean status) {
        Log.i(RadMQTTDemoApplication.LOG_TAG, "Broker connection status: " + status);
        Intent it = new Intent(EVENT_BROKER_CONNECTION);
        it.putExtra(EVENT_BROKER_CONNECTION_STATUS, status);
        LocalBroadcastManager.getInstance(this.getApplicationContext()).sendBroadcast(it);
    }
}