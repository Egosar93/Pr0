package com.pr0gramm.app.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.view.menu.ActionMenuItem;
import android.support.v7.widget.Toolbar;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;

import com.akodiakson.sdk.simple.Sdk;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.pr0gramm.app.ActivityComponent;
import com.pr0gramm.app.BuildConfig;
import com.pr0gramm.app.R;
import com.pr0gramm.app.Settings;
import com.pr0gramm.app.feed.FeedFilter;
import com.pr0gramm.app.feed.FeedType;
import com.pr0gramm.app.services.BookmarkService;
import com.pr0gramm.app.services.SingleShotService;
import com.pr0gramm.app.services.Track;
import com.pr0gramm.app.services.UserService;
import com.pr0gramm.app.sync.SyncBroadcastReceiver;
import com.pr0gramm.app.ui.base.BaseAppCompatActivity;
import com.pr0gramm.app.ui.dialogs.UpdateDialogFragment;
import com.pr0gramm.app.ui.fragments.DrawerFragment;
import com.pr0gramm.app.ui.fragments.FavoritesFragment;
import com.pr0gramm.app.ui.fragments.FeedFragment;
import com.pr0gramm.app.ui.fragments.ItemWithComment;
import com.pr0gramm.app.ui.fragments.PostPagerFragment;
import com.pr0gramm.app.vpx.VpxChecker;

import org.joda.time.Duration;
import org.joda.time.Instant;
import org.joda.time.Minutes;
import org.joda.time.Seconds;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import butterknife.Bind;
import rx.Observable;
import rx.functions.Actions;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.pr0gramm.app.services.ThemeHelper.theme;
import static com.pr0gramm.app.ui.dialogs.ErrorDialogFragment.defaultOnError;
import static com.pr0gramm.app.ui.fragments.BusyDialogFragment.busyDialog;


/**
 * This is the main class of our pr0gramm app.
 */
public class MainActivity extends BaseAppCompatActivity implements
        DrawerFragment.OnFeedFilterSelected,
        FragmentManager.OnBackStackChangedListener,
        ScrollHideToolbarListener.ToolbarActivity,
        MainActionHandler, PermissionHelperActivity {

    // we use this to propagate a fake-home event to the fragments.
    public static final int ID_FAKE_HOME = android.R.id.list;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Bind(R.id.drawer_layout)
    DrawerLayout drawerLayout;

    @Bind(R.id.toolbar)
    Toolbar toolbar;

    @Nullable
    @Bind(R.id.toolbar_container)
    View toolbarContainer;

    @Bind(R.id.content)
    View contentContainer;

    @Inject
    UserService userService;

    @Inject
    BookmarkService bookmarkService;

    @Inject
    Settings settings;

    @Inject
    SharedPreferences shared;

    @Inject
    SingleShotService singleShotService;

    @Inject
    PermissionHelper permissionHelper;

    private ActionBarDrawerToggle drawerToggle;
    private ScrollHideToolbarListener scrollHideToolbarListener;
    private boolean startedWithIntent;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Sdk.isAtLeastLollipop()) {
            // enable transition on lollipop and above
            supportRequestWindowFeature(Window.FEATURE_ACTIVITY_TRANSITIONS);
        }

        setTheme(theme().translucentStatus);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        // use toolbar as action bar
        setSupportActionBar(toolbar);

        // and hide it away on scrolling
        scrollHideToolbarListener = new ScrollHideToolbarListener(
                firstNonNull(toolbarContainer, toolbar));

        // prepare drawer layout
        drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.app_name, R.string.app_name);
        drawerLayout.setDrawerShadow(R.drawable.drawer_shadow, GravityCompat.START);
        drawerLayout.setDrawerListener(new ForwardDrawerListener(drawerToggle) {
            @Override
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);

                // i am not quite sure if everyone knows that there is a drawer to open.
                Track.drawerOpened();
            }
        });

        //noinspection ConstantConditions
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        drawerToggle.syncState();

        // listen to fragment changes
        getSupportFragmentManager().addOnBackStackChangedListener(this);

        if (savedInstanceState == null) {
            createDrawerFragment();

            Intent intent = getIntent();
            if (intent == null || Intent.ACTION_MAIN.equals(intent.getAction())) {
                // load feed-fragment into view
                gotoFeedFragment(defaultFeedFilter(), true);

            } else {
                startedWithIntent = true;
                onNewIntent(intent);
            }
        }

        if (singleShotService.firstTimeInVersion("changelog")) {
            ChangeLogDialog dialog = new ChangeLogDialog();
            dialog.show(getSupportFragmentManager(), null);

        } else if (shouldShowFeedbackReminder()) {
            //noinspection ResourceType
            Snackbar.make(contentContainer, R.string.feedback_reminder, 10000).show();

        } else {
            // start the update check again
            UpdateDialogFragment.checkForUpdates(this, false);
        }

        addOriginalContentBookmarkOnce();

        VpxChecker.vpxOkay(this).subscribe(okay -> {
            logger.info("Vpx decoder seems to work: {}", okay);
            Track.vpxWouldWork(okay);
        });
    }

    private boolean shouldShowFeedbackReminder() {
        // By design it is | and not ||. We want both conditions to
        // be evaluated for the sideeffects
        return settings.useBetaChannel()
                && (singleShotService.firstTimeInVersion("hint_feedback_reminder")
                | singleShotService.firstTimeToday("hint_feedback_reminder"));
    }

    @Override
    protected void injectComponent(ActivityComponent appComponent) {
        appComponent.inject(this);
    }

    /**
     * Adds a bookmark if there currently are no bookmarks.
     */
    private void addOriginalContentBookmarkOnce() {
        if (!singleShotService.isFirstTime("add_original_content_bookmarks"))
            return;

        bookmarkService.get().first().subscribe(bookmarks -> {
            if (bookmarks.isEmpty()) {
                FeedFilter filter = new FeedFilter()
                        .withFeedType(FeedType.PROMOTED)
                        .withTags("original content");

                bookmarkService.create(filter);
            }
        }, Actions.empty());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (!Intent.ACTION_VIEW.equals(intent.getAction()))
            return;

        handleUri(intent.getData());
    }

    @Override
    protected void onStart() {
        super.onStart();

        // trigger updates while the activity is running
        sendSyncRequest.run();
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(sendSyncRequest);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        getSupportFragmentManager().removeOnBackStackChangedListener(this);

        try {
            super.onDestroy();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onBackStackChanged() {
        updateToolbarBackButton();
        updateActionbarTitle();

        DrawerFragment drawer = getDrawerFragment();
        if (drawer != null) {
            FeedFilter currentFilter = getCurrentFeedFilter();

            // show the current item in the drawer
            drawer.updateCurrentFilters(currentFilter);
        }

        if (BuildConfig.DEBUG) {
            printFragmentStack();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        Settings.VolumeNavigationType navigationType = settings.volumeNavigation();
        Fragment fragment = getCurrentFragment();
        if (fragment instanceof PostPagerFragment && navigationType != Settings.VolumeNavigationType.DISABLED) {
            PostPagerFragment pager = (PostPagerFragment) fragment;

            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (navigationType == Settings.VolumeNavigationType.UP) {
                    pager.moveToNext();
                } else {
                    pager.moveToPrev();
                }

                return true;
            }

            if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (navigationType == Settings.VolumeNavigationType.UP) {
                    pager.moveToPrev();
                } else {
                    pager.moveToNext();
                }

                return true;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    private void printFragmentStack() {
        List<String> names = new ArrayList<>();
        for (int idx = 0; idx < getSupportFragmentManager().getBackStackEntryCount(); idx++) {
            FragmentManager.BackStackEntry entry = getSupportFragmentManager().getBackStackEntryAt(idx);
            names.add(entry.getName());
        }

        logger.info("stack: root -> {}", Joiner.on(" -> ").join(names));
    }

    private void updateActionbarTitle() {
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            FeedFilter filter = getCurrentFeedFilter();
            if (filter == null) {
                bar.setTitle(R.string.pr0gramm);
                bar.setSubtitle(null);
            } else {
                FeedFilterFormatter.FeedTitle feed = FeedFilterFormatter.format(this, filter);
                bar.setTitle(feed.title);
                bar.setSubtitle(feed.subtitle);
            }
        }
    }

    /**
     * Returns the current feed filter. Might be null, if no filter could be detected.
     */
    @Nullable
    private FeedFilter getCurrentFeedFilter() {
        // get the filter of the visible fragment.
        FeedFilter currentFilter = null;
        Fragment fragment = getCurrentFragment();
        if (fragment instanceof FilterFragment) {
            currentFilter = ((FilterFragment) fragment).getCurrentFilter();
        }

        return currentFilter;
    }

    private Fragment getCurrentFragment() {
        return getSupportFragmentManager().findFragmentById(R.id.content);
    }

    private boolean shouldClearOnIntent() {
        return !(getCurrentFragment() instanceof FavoritesFragment)
                && getSupportFragmentManager().getBackStackEntryCount() == 0;
    }

    private void updateToolbarBackButton() {
        drawerToggle.setDrawerIndicatorEnabled(shouldClearOnIntent());
        drawerToggle.syncState();
    }

    private void createDrawerFragment() {
        DrawerFragment fragment = new DrawerFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.left_drawer, fragment)
                .commit();
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        drawerToggle.syncState();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        drawerToggle.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!drawerToggle.isDrawerIndicatorEnabled()) {
            if (item.getItemId() == android.R.id.home) {
                if (!dispatchFakeHomeEvent(item))
                    onBackPressed();

                return true;
            }
        }

        return drawerToggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
    }

    private boolean dispatchFakeHomeEvent(MenuItem item) {
        return onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, new ActionMenuItem(
                this, item.getGroupId(), ID_FAKE_HOME, 0, item.getOrder(), item.getTitle()));
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawers();
            return;
        }

        // at the end, go back to the "top" page before stopping everything.
        if (getSupportFragmentManager().getBackStackEntryCount() == 0 && !startedWithIntent) {
            FeedFilter filter = getCurrentFeedFilter();
            if (filter != null && !isDefaultFilter(filter)) {
                gotoFeedFragment(defaultFeedFilter(), true);
                return;
            }
        }

        super.onBackPressed();
    }

    private boolean isDefaultFilter(FeedFilter filter) {
        return defaultFeedFilter().equals(filter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        onBackStackChanged();
    }


    @Override
    public void onLogoutClicked() {
        drawerLayout.closeDrawers();
        Track.logout();

        userService.logout()
                .compose(bindToLifecycle())
                .lift(busyDialog(this))
                .doOnCompleted(() -> {
                    // show a short information.
                    Snackbar.make(drawerLayout, R.string.logout_successful_hint, Snackbar.LENGTH_SHORT).show();

                    // reset everything!
                    gotoFeedFragment(defaultFeedFilter(), true);
                })
                .subscribe(Actions.empty(), defaultOnError());
    }

    private FeedFilter defaultFeedFilter() {
        FeedType type = settings.feedStartAtNew() ? FeedType.NEW : FeedType.PROMOTED;
        return new FeedFilter().withFeedType(type);
    }

    @Override
    public void onFeedFilterSelectedInNavigation(FeedFilter filter) {
        gotoFeedFragment(filter, true);
        drawerLayout.closeDrawers();
    }

    @Override
    public void onOtherNavigationItemClicked() {
        drawerLayout.closeDrawers();
    }

    @Override
    public void onNavigateToFavorites(String username) {
        // move to new fragment
        FavoritesFragment fragment = FavoritesFragment.newInstance(username);
        moveToFragment(fragment, true);

        drawerLayout.closeDrawers();
    }

    @Override
    public void onUsernameClicked() {
        Optional<String> name = userService.getName();
        if (name.isPresent()) {
            FeedFilter filter = new FeedFilter()
                    .withFeedType(FeedType.NEW)
                    .withUser(name.get());

            gotoFeedFragment(filter, false);
        }

        drawerLayout.closeDrawers();
    }

    @Override
    public void onFeedFilterSelected(FeedFilter filter) {
        gotoFeedFragment(filter, false);
    }

    @Override
    public void pinFeedFilter(FeedFilter filter, String title) {
        bookmarkService.create(filter, title).subscribe(Actions.empty(), defaultOnError());
        drawerLayout.openDrawer(GravityCompat.START);
    }

    private void gotoFeedFragment(FeedFilter newFilter, boolean clear) {
        gotoFeedFragment(newFilter, clear, Optional.absent());
    }

    private void gotoFeedFragment(FeedFilter newFilter, boolean clear, Optional<ItemWithComment> start) {
        moveToFragment(FeedFragment.newInstance(newFilter, start), clear);
    }

    private void moveToFragment(Fragment fragment, boolean clear) {
        if (isFinishing())
            return;

        if (clear) {
            clearBackStack();
        }

        // and show the fragment
        @SuppressLint("CommitTransaction")
        FragmentTransaction transaction = getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content, fragment);

        if (!clear) {
            logger.info("Adding fragment {} to backstrack", fragment.getClass().getName());
            transaction.addToBackStack("Feed" + fragment);
        }

        try {
            transaction.commit();
        } catch (IllegalStateException ignored) {
        }

        // trigger a back-stack changed after adding the fragment.
        new Handler().post(this::onBackStackChanged);
    }

    private DrawerFragment getDrawerFragment() {
        return (DrawerFragment) getSupportFragmentManager()
                .findFragmentById(R.id.left_drawer);
    }

    private void clearBackStack() {
        try {
            getSupportFragmentManager().popBackStackImmediate(
                    null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        } catch (Exception ignored) {
        }
    }

    @Override
    public ScrollHideToolbarListener getScrollHideToolbarListener() {
        return scrollHideToolbarListener;
    }

    /**
     * Handles a uri to something on pr0gramm
     *
     * @param uri The uri to handle
     */
    private void handleUri(Uri uri) {
        Optional<FeedFilterWithStart> result = FeedFilterWithStart.fromUri(uri);
        if (result.isPresent()) {
            FeedFilter filter = result.get().getFilter();
            Optional<ItemWithComment> start = result.get().getStart();

            boolean clear = shouldClearOnIntent();
            gotoFeedFragment(filter, clear, start);

        } else {
            gotoFeedFragment(defaultFeedFilter(), true);
        }
    }

    @SuppressWarnings("Convert2Lambda")
    private final Runnable sendSyncRequest = new Runnable() {
        private Instant lastUpdate = new Instant(0);

        @Override
        public void run() {
            Instant now = Instant.now();
            if (Seconds.secondsBetween(lastUpdate, now).getSeconds() > 45) {
                SyncBroadcastReceiver.syncNow(MainActivity.this);
            }

            // reschedule
            Duration delay = Minutes.minutes(1).toStandardDuration();
            handler.postDelayed(this, delay.getMillis());

            // and remember the last update time
            lastUpdate = now;
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public Observable<Void> requirePermission(String permission) {
        return permissionHelper.requirePermission(permission);
    }
}
