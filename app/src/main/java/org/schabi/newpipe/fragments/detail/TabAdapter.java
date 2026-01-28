package org.schabi.newpipe.fragments.detail;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import java.util.ArrayList;
import java.util.List;

public class TabAdapter extends FragmentPagerAdapter {
    private final List<Fragment> mFragmentList = new ArrayList<>();
    private final List<String> mFragmentTitleList = new ArrayList<>();
    private final List<Long> mFragmentIdList = new ArrayList<>();
    private final FragmentManager fragmentManager;
    private long nextFragmentId = 0L;

    public TabAdapter(final FragmentManager fm) {
        // if changed to BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT => crash if enqueueing stream in
        // the background and then clicking on it to open VideoDetailFragment:
        // "Cannot setMaxLifecycle for Fragment not attached to FragmentManager"
        super(fm, BEHAVIOR_SET_USER_VISIBLE_HINT);
        this.fragmentManager = fm;
    }

    @NonNull
    @Override
    public Fragment getItem(final int position) {
        return mFragmentList.get(position);
    }

    @Override
    public int getCount() {
        return mFragmentList.size();
    }

    public void addFragment(final Fragment fragment, final String title) {
        mFragmentList.add(fragment);
        mFragmentTitleList.add(title);
        mFragmentIdList.add(nextFragmentId++);
    }

    public void clearAllItems() {
        mFragmentList.clear();
        mFragmentTitleList.clear();
        mFragmentIdList.clear();
    }

    public void removeItem(final int position) {
        final int index = position == 0 ? 0 : position - 1;
        mFragmentList.remove(index);
        mFragmentTitleList.remove(index);
        mFragmentIdList.remove(index);
    }

    public void updateItem(final int position, final Fragment fragment) {
        mFragmentList.set(position, fragment);
        mFragmentIdList.set(position, nextFragmentId++);
    }

    public void updateItem(final String title, final Fragment fragment) {
        final int index = mFragmentTitleList.indexOf(title);
        if (index != -1) {
            updateItem(index, fragment);
        }
    }

    @Override
    public int getItemPosition(@NonNull final Object object) {
        if (mFragmentList.contains(object)) {
            return mFragmentList.indexOf(object);
        } else {
            return POSITION_NONE;
        }
    }

    @Override
    public long getItemId(final int position) {
        return mFragmentIdList.get(position);
    }

    public int getItemPositionByTitle(final String title) {
        return mFragmentTitleList.indexOf(title);
    }

    @Nullable
    public String getItemTitle(final int position) {
        if (position < 0 || position >= mFragmentTitleList.size()) {
            return null;
        }
        return mFragmentTitleList.get(position);
    }

    public void notifyDataSetUpdate() {
        notifyDataSetChanged();
    }

    @Override
    public void destroyItem(@NonNull final ViewGroup container,
                            final int position,
                            @NonNull final Object object) {
        fragmentManager.beginTransaction().remove((Fragment) object).commitNowAllowingStateLoss();
    }

}
