package com.red.alert.activities.settings.alerts;

import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;

import com.red.alert.R;
import com.red.alert.activities.Map;
import com.red.alert.config.Logging;
import com.red.alert.config.NotificationChannels;
import com.red.alert.config.ThreatTypes;
import com.red.alert.logic.communication.broadcasts.LocationAlertsEvents;
import com.red.alert.logic.communication.intents.AlertViewParameters;
import com.red.alert.logic.location.LocationLogic;
import com.red.alert.logic.push.PushManager;
import com.red.alert.logic.services.ServiceManager;
import com.red.alert.logic.settings.AppPreferences;
import com.red.alert.model.Alert;
import com.red.alert.services.location.LocationService;
import com.red.alert.ui.activities.AppCompatPreferenceActivity;
import com.red.alert.ui.compatibility.ProgressDialogCompat;
import com.red.alert.ui.dialogs.AlertDialogBuilder;
import com.red.alert.ui.elements.SliderPreference;
import com.red.alert.ui.localization.rtl.RTLSupport;
import com.red.alert.utils.backend.RedAlertAPI;
import com.red.alert.utils.caching.Singleton;
import com.red.alert.utils.communication.Broadcasts;
import com.red.alert.utils.feedback.Volume;
import com.red.alert.utils.formatting.StringUtils;
import com.red.alert.utils.integration.GooglePlayServices;
import com.red.alert.utils.localization.DateTime;
import com.red.alert.utils.localization.Localization;
import com.red.alert.utils.metadata.LocationData;
import com.red.alert.utils.threading.AsyncTaskAdapter;

import java.util.ArrayList;
import java.util.List;

import me.pushy.sdk.lib.jackson.core.JsonProcessingException;

public class LocationAlerts extends AppCompatPreferenceActivity {
    Preference mNearbyCities;
    SliderPreference mFrequency;
    SliderPreference mMaxDistance;
    CheckBoxPreference mLocationAlerts;

    private SharedPreferences.OnSharedPreferenceChangeListener mBroadcastListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences Preferences, String Key) {
            // Got new location?
            if (Key.equalsIgnoreCase(LocationAlertsEvents.LOCATION_RECEIVED)) {
                // Refresh setting summaries with new values
                refreshSummaries();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this);

        // Load UI elements
        initializeUI();

        // Must have Google Play Services for this to work
        verifyGooglePlayServicesAvailable();

        // Volume keys should control alert volume
        Volume.setVolumeKeysAction(this);
    }

    private void verifyGooglePlayServicesAvailable() {
        // Show dialog
        if (!GooglePlayServices.isAvailable(this)) {
            // Show error
            AlertDialogBuilder.showGenericDialog(getString(R.string.error), getString(R.string.noGooglePlayServices), getString(R.string.okay), null, false, LocationAlerts.this, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // No go
                    finish();
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Unregister for broadcasts
        Broadcasts.unsubscribe(this, mBroadcastListener);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this);

        // Location alerts enabled and permission not granted?
        if (mLocationAlerts.isChecked() && !LocationLogic.isLocationAccessGranted(this)) {
            // Disable location alerts
            mLocationAlerts.setChecked(false);

            // Stop the location service
            ServiceManager.stopLocationService(LocationAlerts.this);

            // Request permission via dialog
            LocationLogic.showLocationAccessRequestDialog(this);
        }

        // Register for broadcasts
        Broadcasts.subscribe(this, mBroadcastListener);

        // Refresh nearby cities display
        refreshSummaries();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Support for RTL languages
        RTLSupport.mirrorActionBar(this);
    }

    @Override
    protected void attachBaseContext(Context base) {
        // Reapply locale
        Localization.overridePhoneLocale(base);
        super.attachBaseContext(base);
    }

    private void initializeUI() {
        // Allow click on home button
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // Load settings from XML (there is no non-deprecated way to do it on API level 7)
        addPreferencesFromResource(R.xml.settings_location_alerts);

        // Cache resource IDs
        mLocationAlerts = (CheckBoxPreference) findPreference(getString(R.string.locationAlertsPref));
        mFrequency = (SliderPreference) findPreference(getString(R.string.gpsFrequencyPref));
        mMaxDistance = (SliderPreference) findPreference(getString(R.string.maxDistancePref));
        mNearbyCities = findPreference(getString(R.string.nearbyCitiesPref));

        // Set initial value
        refreshSummaries();

        // Set up listeners
        initializeListeners();
    }

    private void initializeListeners() {
        // Max distance changed
        mMaxDistance.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                // Wait until value is saved
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Set initial value
                        refreshSummaries();

                        // Update location service foreground notification nearby cities display
                        updateLocationService();
                    }
                }, 200);

                // Save value
                return true;
            }
        });

        // Location alerts toggled
        mLocationAlerts.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                // Trying to enable location-based alerts?
                if ((boolean)newValue == true) {
                    // Can we access the user's location?
                    if (!LocationLogic.isLocationAccessGranted(LocationAlerts.this)) {
                        // Request permission via dialog
                        LocationLogic.showLocationAccessRequestDialog(LocationAlerts.this);

                        // Don't enable location alerts yet
                        return false;
                    }

                    // Start the location service
                    ServiceManager.startLocationService(LocationAlerts.this);
                }
                else {
                    // Stop the location service
                    ServiceManager.stopLocationService(LocationAlerts.this);
                }

                // Update subscriptions on the server-side
                new UpdateSubscriptionsAsync().execute();

                // Save new value
                return true;
            }
        });

        // Max distance changed
        mMaxDistance.setSeekBarChangedListener(new SliderPreference.onSeekBarChangedListener() {
            @Override
            public String getDialogMessage(float currentValue) {
                // Generate a new summary with the selected value
                return getMaxDistanceSummary(currentValue);
            }
        });

        // Frequency changed - update dialog text
        mFrequency.setSeekBarChangedListener(new SliderPreference.onSeekBarChangedListener() {
            @Override
            public String getDialogMessage(float value) {
                // Generate a new summary with the selected value
                return getFrequencySummary(value);
            }
        });

        // Frequency saved
        mFrequency.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object value) {
                // Wait until value is saved
                new Handler().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        // Set initial value
                        refreshSummaries();

                        // Update location service polling interval
                        updateLocationService();
                    }
                }, 200);

                // Save value
                return true;
            }
        });

        // Nearby cities click listener
        mNearbyCities.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                // Get current location
                Location location = LocationLogic.getCurrentLocation(LocationAlerts.this);

                // No location?
                if (location == null) {
                    return false;
                }

                // Get nearby cities
                List<String> nearbyCities = LocationData.getNearbyCities(location, LocationAlerts.this);

                // Prepare mock alerts list for map activity
                List<Alert> mockAlerts = new ArrayList<>();

                // Travers nearby cities
                for (String city : nearbyCities) {
                    // Create mock alert object for map display
                    Alert alert = new Alert();

                    // Set mock alert params
                    alert.city = city;
                    alert.date = DateTime.getUnixTimestamp();
                    alert.threat = ThreatTypes.NEARBY_CITIES_DISPLAY;

                    // Add to list of mock alerts
                    mockAlerts.add(alert);
                }

                // Create new intent
                Intent map = new Intent();

                // Set activity class
                map.setClass(LocationAlerts.this, Map.class);

                try {
                    // Pass grouped alerts as JSON
                    map.putExtra(AlertViewParameters.ALERTS, Singleton.getJackson().writer().writeValueAsString(mockAlerts));
                } catch (JsonProcessingException e) {
                    // Show error dialog
                    AlertDialogBuilder.showGenericDialog(getString(R.string.error), e.getMessage(), getString(R.string.okay), null, false, LocationAlerts.this, null);
                    return false;
                }

                // Open map
                startActivity(map);

                // Click was handled
                return true;
            }
        });
    }

    public void updateLocationService() {
        // Bind to our location service
        bindService(new Intent(this, LocationService.class), new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName componentName, IBinder binder) {
                // Convert binder to LocalBinder
                LocationService.LocalBinder localBinder = (LocationService.LocalBinder) binder;

                // Get service instance
                LocationService service = localBinder.getService();

                // Apply new location service params
                service.updateLocationServiceParams();

                // Unbind fom service
                unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName componentName) {
                // Do nothing
            }
        }, Context.BIND_AUTO_CREATE);
    }

    private String getFrequencySummary(float OverrideValue) {
        // Construct summary text
        return getString(R.string.gpsFrequencyDesc) + "\r\n(" + getString(R.string.every) + " " + LocationLogic.getUpdateIntervalMinutes(this, OverrideValue) + " " + getString(R.string.minutes) + ")";
    }

    private String getMaxDistanceSummary(float OverrideValue) {
        // Construct summary text
        return getString(R.string.maxDistanceDesc) + "\r\n(" + LocationLogic.getMaxDistanceKilometers(this, OverrideValue) + " " + getString(R.string.kilometer) + ")";
    }

    private void refreshSummaries() {
        // Update summary text
        mMaxDistance.setSummary(Localization.localizeDigits(getMaxDistanceSummary(-1), this));

        // Update summary text
        mFrequency.setSummary(Localization.localizeDigits(getFrequencySummary(-1), this));

        // Get current location
        Location location = LocationLogic.getCurrentLocation(this);

        // Result string
        String nearby;

        // Location alerts disabled?
        if (!mLocationAlerts.isChecked()) {
            nearby = "";
        }
        // No recent location?
        else if (location == null) {
            // Show error
            nearby = getString(R.string.noLocation);
        }
        else {
            // Get nearby cities
            nearby = LocationData.getNearbyCityNames(location, this);

            // No results?
            if (StringUtils.stringIsNullOrEmpty(nearby)) {
                // Show error
                nearby = getString(R.string.noNearbyCities);
            }
        }

        // Update summary text
        mNearbyCities.setSummary(nearby);

        // Disable/enable nearby cities setting based on checkbox value
        mNearbyCities.setEnabled(mLocationAlerts.isChecked());
    }

    public boolean onOptionsItemSelected(final MenuItem Item) {
        // Check item ID
        switch (Item.getItemId()) {
            // Home button?
            case android.R.id.home:
                onBackPressed();
        }

        return super.onOptionsItemSelected(Item);
    }

    public class UpdateSubscriptionsAsync extends AsyncTaskAdapter<Integer, String, Exception> {
        ProgressDialog mLoading;

        public UpdateSubscriptionsAsync() {
            // Fix progress dialog appearance on old devices
            mLoading = ProgressDialogCompat.getStyledProgressDialog(LocationAlerts.this);

            // Prevent cancel
            mLoading.setCancelable(false);

            // Set default message
            mLoading.setMessage(getString(R.string.loading));

            // Show the progress dialog
            mLoading.show();
        }

        @Override
        protected Exception doInBackground(Integer... Parameter) {
            try {
                // Update notification preferences
                PushManager.updateSubscriptions(LocationAlerts.this);

                // Subscribe for alerts based on current city/region selections
                RedAlertAPI.subscribe(LocationAlerts.this);
            } catch (Exception exc) {
                // Return exception to onPostExecute
                return exc;
            }

            // Success
            return null;
        }

        @Override
        protected void onPostExecute(Exception exc) {
            // Failed?
            if (exc != null) {
                // Log it
                Log.e(Logging.TAG, "Updating subscriptions failed", exc);

                // Restore previous notification toggle state
                SharedPreferences.Editor editor = Singleton.getSharedPreferences(LocationAlerts.this).edit();

                // Restore original values
                editor.putBoolean(getString(R.string.locationAlertsPref), !AppPreferences.getLocationAlertsEnabled(LocationAlerts.this));

                // Save and flush to disk
                editor.commit();
            }

            // Activity dead?
            if (isFinishing() || isDestroyed()) {
                return;
            }

            // Hide loading dialog
            if (mLoading.isShowing()) {
                mLoading.dismiss();
            }

            // Refresh nearby cities
            refreshSummaries();

            // Show error if failed
            if (exc != null) {
                // Build an error message
                String errorMessage = getString(R.string.apiRequestFailed) + "\n\n" + exc.getMessage() + "\n\n" + exc.getCause();

                // Build the dialog
                AlertDialogBuilder.showGenericDialog(getString(R.string.error), errorMessage, getString(R.string.okay), null, false, LocationAlerts.this, null);
            }
            else {
                // Location alerts enabled?
                if (mLocationAlerts.isChecked()) {
                    // Android O and newer required for notification channels
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Show dialog instructing user on how to hide the location alerts foreground service notification
                        AlertDialogBuilder.showGenericDialog(getString(R.string.hideGPSForegroundNotification), getString(R.string.hideGPSForegroundNotificationInstructions), getString(R.string.okay), getString(R.string.notNow), true, LocationAlerts.this, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int which) {
                                // Clicked okay?
                                if (which == DialogInterface.BUTTON_POSITIVE) {
                                    // Open notification channel config to allow user to easily disable the notification channel
                                    Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                                    intent.putExtra(Settings.EXTRA_CHANNEL_ID, NotificationChannels.LOCATION_SERVICE_FOREGROUND_NOTIFICATION_CHANNEL_ID);
                                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                                    startActivity(intent);
                                }
                                }
                        });
                    }
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Just granted location permission?
        if (requestCode == LocationLogic.LOCATION_PERMISSION_REQUEST_CODE && grantResults.length > 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
            // Enable location alerts
            mLocationAlerts.setChecked(true);
            mLocationAlerts.getOnPreferenceChangeListener().onPreferenceChange(null, true);
        }
    }
}
