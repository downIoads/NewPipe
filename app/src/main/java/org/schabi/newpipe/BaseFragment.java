package org.schabi.newpipe;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.evernote.android.state.State;
import com.livefront.bridge.Bridge;


public abstract class BaseFragment extends Fragment {
    protected final String TAG = getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    protected static final boolean DEBUG = MainActivity.DEBUG;
    protected AppCompatActivity activity;
    //These values are used for controlling fragments when they are part of the frontpage
    @State
    protected boolean useAsFrontPage = false;

    public void useAsFrontPage(final boolean value) {
        useAsFrontPage = value;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Fragment's Lifecycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);
        activity = (AppCompatActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        activity = null;
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }
        super.onCreate(savedInstanceState);
        Bridge.restoreInstanceState(this, savedInstanceState);
        if (savedInstanceState != null) {
            onRestoreInstanceState(savedInstanceState);
        }
    }


    @Override
    public void onViewCreated(@NonNull final View rootView, final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onViewCreated() called with: "
                    + "rootView = [" + rootView + "], "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }
        initViews(rootView, savedInstanceState);
        initListeners();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        Bridge.saveInstanceState(this, outState);
    }

    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * This method is called in {@link #onViewCreated(View, Bundle)} to initialize the views.
     *
     * <p>
     * {@link #initListeners()} is called after this method to initialize the corresponding
     * listeners.
     * </p>
     * @param rootView The inflated view for this fragment
     *                 (provided by {@link #onViewCreated(View, Bundle)})
     * @param savedInstanceState The saved state of this fragment
 *                               (provided by {@link #onViewCreated(View, Bundle)})
     */
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
    }

    /**
     * Initialize the listeners for this fragment.
     *
     * <p>
     * This method is called after {@link #initViews(View, Bundle)}
     * in {@link #onViewCreated(View, Bundle)}.
     * </p>
     */
    protected void initListeners() {
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    public void setTitle(final String title) {
        if (DEBUG) {
            Log.d(TAG, "setTitle() called with: title = [" + title + "]");
        }
        if (!useAsFrontPage && activity != null && activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
            activity.getSupportActionBar().setTitle(title);
        }
    }

    /**
     * Show the given text in the centered toolbar title (see {@code R.id.toolbar_title}) instead
     * of the default left-aligned {@link androidx.appcompat.app.ActionBar} title. Fragments that
     * use this must call {@link #hideCenteredTitle()} when they are no longer visible (e.g. in
     * {@code onPause()}), so other screens keep their normal left-aligned title.
     *
     * @param title the title to display, centered
     */
    protected void showCenteredTitle(final String title) {
        if (activity == null) {
            return;
        }
        final TextView toolbarTitle = activity.findViewById(R.id.toolbar_title);
        if (toolbarTitle != null) {
            toolbarTitle.setText(title);
            toolbarTitle.setVisibility(View.VISIBLE);
        }
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayShowTitleEnabled(false);
            activity.getSupportActionBar().setTitle("");
        }
    }

    protected void hideCenteredTitle() {
        if (activity == null) {
            return;
        }
        final TextView toolbarTitle = activity.findViewById(R.id.toolbar_title);
        if (toolbarTitle != null) {
            toolbarTitle.setVisibility(View.GONE);
        }
        if (activity.getSupportActionBar() != null) {
            activity.getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
    }

    /**
     * Finds the root fragment by looping through all of the parent fragments. The root fragment
     * is supposed to be {@link org.schabi.newpipe.fragments.MainFragment}, and is the fragment that
     * handles keeping the backstack of opened fragments in NewPipe, and also the player bottom
     * sheet. This function therefore returns the fragment manager of said fragment.
     *
     * @return the fragment manager of the root fragment, i.e.
     *         {@link org.schabi.newpipe.fragments.MainFragment}
     */
    protected FragmentManager getFM() {
        Fragment current = this;
        while (current.getParentFragment() != null) {
            current = current.getParentFragment();
        }
        return current.getFragmentManager();
    }
}
