package fr.fouss.boardeo;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.Circle;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import fr.fouss.boardeo.data.Board;
import fr.fouss.boardeo.sign_in.SignInActivity;
import fr.fouss.boardeo.utils.UserUtils;

public class HomeActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        OnMapReadyCallback,
        GoogleMap.OnInfoWindowClickListener,
        View.OnClickListener {

    private static final String REQUESTING_LOCATION_UPDATES_KEY = "REQUESTING_LOCATION_UPDATES";
    private static final int REQUEST_CODE_LOCATION_ALLOWANCE = 6942;
    private static final int REQUEST_CODE_LOCATION_ACTIVATE = 7357;

    /**
     * Firebase database instance
     */
    private DatabaseReference mDatabase;
    private ChildEventListener mNearbyBoardsListener;
    private Map<String, Marker> markerList;
    private Map<String, Board> boardList;
    private Circle mCircle;

    private UserUtils userUtils;

    private TextView usernameLabel;

    private double radius;
    private LatLng lastPosition;

    private GoogleMap mMap;
    private LocationRequest mLocationRequest;
    private FusedLocationProviderClient mFusedLocationClient;
    private LocationCallback mLocationCallback;
    private Boolean mRequestingLocationUpdates;
    private LocationManager mLocationManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        updateValuesFromBundle(savedInstanceState);

        mDatabase = FirebaseDatabase.getInstance().getReference();
        userUtils = new UserUtils(this);
        markerList = new HashMap<>();
        boardList = new HashMap<>();

        radius = 100.0;
        lastPosition = new LatLng(0.0, 0.0);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        View navHeaderView = navigationView.getHeaderView(0);
        usernameLabel = navHeaderView.findViewById(R.id.username);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // Setup the location activation and allowance buttons
        findViewById(R.id.allow_location_button).setOnClickListener(this);
        findViewById(R.id.activate_location_button).setOnClickListener(this);

        // Setup all location based settings
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(20000);
        mLocationRequest.setFastestInterval(10000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        // Get the location provider
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Create the location callback
        mLocationCallback = new BoardeoLocationCallback();

        mRequestingLocationUpdates = true;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (userUtils.isSignedIn()) {
            usernameLabel.setText(userUtils.getUserName());

            // Updates the user's name in database
            mDatabase.child("users").child(userUtils.getUserUid()).child("username").setValue(userUtils.getUserName());

            // Check for location permission
            if (ActivityCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                findViewById(R.id.allow_location_button).setVisibility(View.VISIBLE);
                return;
            }

            if (mLocationManager == null)
                mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (mLocationManager == null || !mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                findViewById(R.id.activate_location_button).setVisibility(View.VISIBLE);
                return;
            }

            startLocationUpdates();

        } else {
            startActivity(new Intent(this, SignInActivity.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        stopLocationUpdates();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDatabase.child("users").child(userUtils.getUserUid()).child("lastConnection").setValue(new Date().getTime());
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(REQUESTING_LOCATION_UPDATES_KEY, mRequestingLocationUpdates);
        super.onSaveInstanceState(outState);
    }

    private void updateValuesFromBundle(Bundle savedInstanceState) {
        // Update the value of mRequestingLocationUpdates from the Bundle.
        if (savedInstanceState != null &&
                savedInstanceState.keySet().contains(REQUESTING_LOCATION_UPDATES_KEY)) {
            mRequestingLocationUpdates = savedInstanceState.getBoolean(
                    REQUESTING_LOCATION_UPDATES_KEY);
        }
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.home, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.action_sort_by_distance:
                sortByDistanceDialog();
                return true;
//            case R.id.action_sort_by_tags:
//                sortByTagsDialog();
//                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void sortByDistanceDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_sort_by_distance);
        dialog.setCancelable(true);

        TextView distanceLabel = dialog.findViewById(R.id.distanceLabel);
        distanceLabel.setText(String.format(Locale.getDefault(), "%d", (int) radius));

        SeekBar distanceBar = dialog.findViewById(R.id.distanceBar);
        distanceBar.setProgress((int) radius);
        distanceBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                distanceLabel.setText(String.format(Locale.getDefault(), "%d", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        dialog.findViewById(R.id.cancelButton).setOnClickListener(v -> dialog.cancel());
        dialog.findViewById(R.id.applyButton).setOnClickListener(v -> {
            radius = (double) distanceBar.getProgress();
            displayNearbyBoards(lastPosition.latitude, lastPosition.longitude);
            dialog.dismiss();
        });

        dialog.show();
    }

    private void sortByTagsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_sort_by_tags);
        dialog.setCancelable(true);

        dialog.show();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        switch (item.getItemId()) {
            case R.id.nav_home:
                break;
            case R.id.nav_my_boards:
                startActivity(new Intent(this, BoardListActivity.class));
                break;
            case R.id.nav_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                break;
            case R.id.nav_sign_out:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(getString(R.string.dialog_content_sign_out))
                        .setTitle(getString(R.string.dialog_title_sign_out));
                builder.setPositiveButton("Yes", (dialog, id1) -> {
                    userUtils.signOut();
                    startActivity(new Intent(this, SignInActivity.class));
                });
                builder.setNegativeButton("No", (dialog, id1) -> {});
                AlertDialog dialog = builder.create();
                dialog.show();
                break;
        }

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    private void displayNearbyBoards(Double latitude, Double longitude) {
        DatabaseReference dataReference = mDatabase.child("boards");

        if (mNearbyBoardsListener != null)
            dataReference.removeEventListener(mNearbyBoardsListener);

        mNearbyBoardsListener = new ChildEventListener() {

            @Override
            public void onChildAdded(DataSnapshot dataSnapshot, String s) {
                updateMarker(dataSnapshot.getKey(), dataSnapshot.getValue(Board.class), latitude, longitude);
            }

            @Override
            public void onChildChanged(DataSnapshot dataSnapshot, String s) {
                updateMarker(dataSnapshot.getKey(), dataSnapshot.getValue(Board.class), latitude, longitude);
            }

            @Override
            public void onChildRemoved(DataSnapshot dataSnapshot) {
                removeMarker(dataSnapshot.getKey());
            }

            @Override
            public void onChildMoved(DataSnapshot dataSnapshot, String s) {
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Toast.makeText(HomeActivity.this,
                        "Board info couldn't be retrieved",
                        Toast.LENGTH_SHORT).show();
            }
        };

        dataReference.addChildEventListener(mNearbyBoardsListener);

        mCircle.setRadius(radius);
        mCircle.setCenter(new LatLng(latitude, longitude));
    }

    private Boolean isInRadius(Double centerLatitude,
                               Double centerLongitude,
                               Double radius,
                               Double objectLatitude,
                               Double objectLongitude) {
        Double objectDistance = Math.sqrt(
                Math.pow((centerLatitude - objectLatitude), 2)
                        + Math.pow((centerLongitude - objectLongitude), 2));
        return (objectDistance * 111000.0) <= radius;
    }

    private void addMarker(String key, Board board, Double currentLatitude, Double currentLongitude) {
        if (!markerList.containsKey(key)
                && isInRadius(currentLatitude, currentLongitude, radius, board.getLatitude(), board.getLongitude())) {
            Marker marker = mMap.addMarker(new MarkerOptions()
                    .position(new LatLng(board.getLatitude(), board.getLongitude()))
                    .title(board.getName())
                    .zIndex(2.0f)
                    .visible(false)
                    .snippet(board.getShortDescription()));
            updateMarkerIcon(marker, key);
            marker.setTag(key);
            markerList.put(key, marker);
            boardList.put(key, board);
        }
    }

    private void updateMarker(String key, Board board, Double currentLatitude, Double currentLongitude) {
        Marker marker = markerList.get(key);

        // If the marker already exists
        if (marker != null) {
            // If it is still in range (it should be updated)
            if (isInRadius(currentLatitude, currentLongitude, radius, board.getLatitude(), board.getLongitude())) {
                marker.setPosition(new LatLng(board.getLatitude(), board.getLongitude()));
                marker.setTitle(board.getName());
                marker.setSnippet(board.getShortDescription());
                updateMarkerIcon(marker, key);

            // If it is not anymore in range (it should be deleted)
            } else {
                removeMarker(key);
            }

        // If it doesn't exist (it should maybe be created)
        } else {
            addMarker(key, board, currentLatitude, currentLongitude);
        }
    }

    private void removeMarker(String key) {
        Marker marker = markerList.get(key);
        marker.setTag(null);
        marker.remove();
        markerList.remove(key);
        boardList.remove(key);
    }

    private void updateMarkerIcon(Marker marker, String boardKey) {
        mDatabase.child("users").child(userUtils.getUserUid()).child("subscriptions")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        boolean isSubscribed = false;

                        for (DataSnapshot ds : dataSnapshot.getChildren()) {
                            if (ds.getKey().equals(boardKey))
                                isSubscribed = true;
                        }

                        if (isSubscribed)
                            marker.setIcon(getMarkerIconFromDrawable(getResources().getDrawable(R.drawable.ic_subbed_board)));
                        else
                            marker.setIcon(getMarkerIconFromDrawable(getResources().getDrawable(R.drawable.ic_unsubbed_board)));

                        marker.setVisible(true);
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        throw databaseError.toException();
                    }
                });
    }


    private BitmapDescriptor getMarkerIconFromDrawable(Drawable drawable) {
        Canvas canvas = new Canvas();
        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bitmap);
        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        drawable.draw(canvas);
        return BitmapDescriptorFactory.fromBitmap(bitmap);
    }

    @Override
    public void onInfoWindowClick(Marker marker) {
        String key = (String) marker.getTag();

        // Launch board detail activity
        Intent intent = new Intent(this, BoardDetailsActivity.class);
        intent.putExtra(Board.KEY_FIELD, key);
        startActivity(intent);
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
        mMap.setInfoWindowAdapter(new BoardeoInfoWindowAdapter());
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.setMyLocationEnabled(true);
        }

        mMap.setOnInfoWindowClickListener(this);

        CameraPosition cameraPosition = CameraPosition.builder()
                .target(new LatLng(0.0, 0.0))
                .zoom(17.5f)
                .build();
        mMap.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition));

        mCircle = mMap.addCircle(new CircleOptions()
                .center(new LatLng(0.0, 0.0))
                .clickable(false)
                .radius(radius)
                .zIndex(1.0f)
                .fillColor(getResources().getColor(R.color.mapCircleFillColor))
                .strokeColor(getResources().getColor(R.color.mapCircleStrokeColor))
                .strokeWidth(5.0f));
    }

    private void updateCameraPosition(Location location) {

        mMap.animateCamera(CameraUpdateFactory.newLatLng(
                new LatLng(location.getLatitude(), location.getLongitude())));
    }

    private void startLocationUpdates() {
        if (mRequestingLocationUpdates
                && ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mFusedLocationClient.requestLocationUpdates(mLocationRequest,
                    mLocationCallback,
                    null);
            mRequestingLocationUpdates = false;
        }
    }

    private void stopLocationUpdates() {
        mFusedLocationClient.removeLocationUpdates(mLocationCallback);
        mRequestingLocationUpdates = true;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.allow_location_button:
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION},
                        REQUEST_CODE_LOCATION_ALLOWANCE);
                break;

            case R.id.activate_location_button:
                startActivityForResult(
                        new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                                REQUEST_CODE_LOCATION_ACTIVATE);
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        if (requestCode == REQUEST_CODE_LOCATION_ALLOWANCE) {
            // If request is cancelled, the result arrays are empty.
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                findViewById(R.id.allow_location_button).setVisibility(View.GONE);
                startLocationUpdates();
            } else {
                Toast.makeText(this,
                        "The goal of this app is to use geolocation, so trust us! ;)",
                        Toast.LENGTH_LONG).show();
                findViewById(R.id.allow_location_button).setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_LOCATION_ACTIVATE:
                if (mLocationManager == null)
                    mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                if (mLocationManager != null && mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    findViewById(R.id.activate_location_button).setVisibility(View.GONE);
                break;
        }
    }

    private class BoardeoLocationCallback extends LocationCallback {

        @Override
        public void onLocationResult(LocationResult locationResult) {
            if (ActivityCompat.checkSelfPermission(HomeActivity.this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                Location location = locationResult.getLastLocation();
                updateCameraPosition(location);
                displayNearbyBoards(location.getLatitude(), location.getLongitude());
                lastPosition = new LatLng(location.getLatitude(), location.getLongitude());
            }
        }
    }

    /** Demonstrates customizing the info window and/or its contents. */
    class BoardeoInfoWindowAdapter implements GoogleMap.InfoWindowAdapter {

        private final View mContents;

        BoardeoInfoWindowAdapter() {
            mContents = getLayoutInflater().inflate(R.layout.maps_info_contents, null);
        }

        @Override
        public View getInfoWindow(Marker marker) {
            // This means that getInfoContents will be called.
            render(marker, mContents);
            return mContents;
        }

        @Override
        public View getInfoContents(Marker marker) {
            return null;
        }

        private void render(Marker marker, View view) {
            String key = (String) marker.getTag();

            int color = boardList.get(key).getColor().intValue();
            LinearLayout colorBanner = view.findViewById(R.id.colorBanner);
            colorBanner.setBackgroundColor(color);

            String title = marker.getTitle();
            TextView titleUi = view.findViewById(R.id.title);
            if (title != null) {
                titleUi.setText(title);

                Boolean isPublic = boardList.get(key).getIsPublic();
                if (isPublic)
                    titleUi.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_public, 0, 0, 0);
                else
                    titleUi.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_private, 0, 0, 0);
            } else {
                titleUi.setText("");
            }

            String snippet = marker.getSnippet();
            TextView snippetUi = view.findViewById(R.id.snippet);
            if (snippet != null) {
                snippetUi.setText(snippet);
            } else {
                snippetUi.setText("");
            }
        }
    }
}
