/*
 * Nextcloud Talk application
 *
 * @author Mario Danic
 * Copyright (C) 2017 Mario Danic (mario@lovelyhq.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.nextcloud.talk.controllers;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.view.MenuItemCompat;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.SearchView;
import android.text.InputType;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;

import com.bluelinelabs.conductor.RouterTransaction;
import com.bluelinelabs.conductor.changehandler.HorizontalChangeHandler;
import com.bluelinelabs.conductor.changehandler.VerticalChangeHandler;
import com.bluelinelabs.conductor.internal.NoOpControllerChangeHandler;
import com.nextcloud.talk.R;
import com.nextcloud.talk.activities.CallActivity;
import com.nextcloud.talk.adapters.items.UserItem;
import com.nextcloud.talk.api.NcApi;
import com.nextcloud.talk.api.helpers.api.ApiHelper;
import com.nextcloud.talk.api.models.json.participants.Participant;
import com.nextcloud.talk.api.models.json.rooms.RoomOverall;
import com.nextcloud.talk.api.models.json.sharees.Sharee;
import com.nextcloud.talk.application.NextcloudTalkApplication;
import com.nextcloud.talk.controllers.base.BaseController;
import com.nextcloud.talk.models.RetrofitBucket;
import com.nextcloud.talk.persistence.entities.UserEntity;
import com.nextcloud.talk.utils.bundle.BundleBuilder;
import com.nextcloud.talk.utils.database.user.UserUtils;

import org.parceler.Parcels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;

import autodagger.AutoInjector;
import butterknife.BindView;
import eu.davidea.flexibleadapter.FlexibleAdapter;
import eu.davidea.flexibleadapter.SelectableAdapter;
import eu.davidea.flexibleadapter.common.SmoothScrollLinearLayoutManager;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import retrofit2.HttpException;

@AutoInjector(NextcloudTalkApplication.class)
public class ContactsController extends BaseController implements SearchView.OnQueryTextListener,
        ActionMode.Callback, FlexibleAdapter.OnItemClickListener {

    public static final String TAG = "ContactsController";

    private static final String KEY_SEARCH_QUERY = "ContactsController.searchQuery";

    @Inject
    UserUtils userUtils;

    @Inject
    NcApi ncApi;
    @BindView(R.id.recycler_view)
    RecyclerView recyclerView;

    @BindView(R.id.swipe_refresh_layout)
    SwipeRefreshLayout swipeRefreshLayout;

    private UserEntity userEntity;
    private Disposable contactsQueryDisposable;
    private Disposable cacheQueryDisposable;
    private FlexibleAdapter<UserItem> adapter;
    private List<UserItem> contactItems = new ArrayList<>();

    private MenuItem searchItem;
    private SearchView searchView;
    private String searchQuery;

    private ActionMode actionMode;

    public ContactsController() {
        super();
        setHasOptionsMenu(true);
    }

    @Override
    protected View inflateView(@NonNull LayoutInflater inflater, @NonNull ViewGroup container) {
        return inflater.inflate(R.layout.controller_generic_rv, container, false);
    }

    @Override
    protected void onViewBound(@NonNull View view) {
        super.onViewBound(view);
        NextcloudTalkApplication.getSharedApplication().getComponentApplication().inject(this);

        userEntity = userUtils.getCurrentUser();

        if (userEntity == null) {
            if (getParentController().getRouter() != null) {
                getParentController().getRouter().setRoot((RouterTransaction.with(new ServerSelectionController())
                        .pushChangeHandler(new HorizontalChangeHandler())
                        .popChangeHandler(new HorizontalChangeHandler())));
            }
        }

        if (adapter == null) {
            adapter = new FlexibleAdapter<>(contactItems, getActivity(), false);
            if (userEntity != null) {
                fetchData();
            }
        }

        adapter.addListener(this);
        prepareViews();
    }

    private void initSearchView() {
        if (getActivity() != null) {
            SearchManager searchManager = (SearchManager) getActivity().getSystemService(Context.SEARCH_SERVICE);
            if (searchItem != null) {
                searchView = (SearchView) MenuItemCompat.getActionView(searchItem);
                searchView.setMaxWidth(Integer.MAX_VALUE);
                searchView.setInputType(InputType.TYPE_TEXT_VARIATION_FILTER);
                searchView.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_FULLSCREEN);
                searchView.setQueryHint(getResources().getString(R.string.nc_search));
                if (searchManager != null) {
                    searchView.setSearchableInfo(searchManager.getSearchableInfo(getActivity().getComponentName()));
                }
                searchView.setOnQueryTextListener(this);
            }
        }

        final View mSearchEditFrame = searchView
                .findViewById(android.support.v7.appcompat.R.id.search_edit_frame);

        BottomNavigationView bottomNavigationView = getParentController().getView().findViewById(R.id.navigation);

        Handler handler = new Handler();
        ViewTreeObserver vto = mSearchEditFrame.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            int oldVisibility = -1;

            @Override
            public void onGlobalLayout() {

                int currentVisibility = mSearchEditFrame.getVisibility();

                if (currentVisibility != oldVisibility) {
                    if (currentVisibility == View.VISIBLE) {
                        handler.postDelayed(() -> bottomNavigationView.setVisibility(View.GONE), 100);
                    } else {
                        handler.postDelayed(() -> {
                            bottomNavigationView.setVisibility(View.VISIBLE);
                            searchItem.setVisible(contactItems.size() > 0);
                        }, 500);
                    }

                    oldVisibility = currentVisibility;
                }

            }
        });

    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.menu_filter, menu);
        searchItem = menu.findItem(R.id.action_search);
        initSearchView();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        searchItem.setVisible(contactItems.size() > 0);
        if (adapter.hasSearchText()) {
            searchItem.expandActionView();
            searchView.setQuery(adapter.getSearchText(), false);
        }
    }

    private void fetchData() {
        dispose(null);

        Set<Sharee> shareeHashSet = new HashSet<>();

        contactItems = new ArrayList<>();

        RetrofitBucket retrofitBucket = ApiHelper.getRetrofitBucketForContactsSearch(userEntity.getBaseUrl(),
                "");
        contactsQueryDisposable = ncApi.getContactsWithSearchParam(
                ApiHelper.getCredentials(userEntity.getUsername(), userEntity.getToken()),
                retrofitBucket.getUrl(), retrofitBucket.getQueryMap())
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(shareesOverall -> {
                            if (shareesOverall != null) {

                                if (shareesOverall.getOcs().getData().getUsers() != null) {
                                    shareeHashSet.addAll(shareesOverall.getOcs().getData().getUsers());
                                }

                                if (shareesOverall.getOcs().getData().getExactUsers() != null &&
                                        shareesOverall.getOcs().getData().getExactUsers().getExactSharees() != null) {
                                    shareeHashSet.addAll(shareesOverall.getOcs().getData().
                                            getExactUsers().getExactSharees());
                                }

                                Participant participant;
                                for (Sharee sharee : shareeHashSet) {
                                    if (!sharee.getValue().getShareWith().equals(userEntity.getUsername())) {
                                        participant = new Participant();
                                        participant.setName(sharee.getLabel());
                                        participant.setUserId(sharee.getValue().getShareWith());
                                        contactItems.add(new UserItem(participant, userEntity));
                                    }

                                }

                                Collections.sort(contactItems, (userItem, t1) ->
                                        userItem.getModel().getName().compareToIgnoreCase(t1.getModel().getName()));

                                adapter.updateDataSet(contactItems, true);
                                searchItem.setVisible(contactItems.size() > 0);
                                swipeRefreshLayout.setRefreshing(false);
                            }

                        }, throwable -> {
                            if (searchItem != null) {
                                searchItem.setVisible(false);
                            }

                            if (throwable instanceof HttpException) {
                                HttpException exception = (HttpException) throwable;
                                switch (exception.code()) {
                                    case 401:
                                        if (getParentController() != null &&
                                                getParentController().getRouter() != null) {
                                            getParentController().getRouter().pushController((RouterTransaction.with
                                                    (new WebViewLoginController(userEntity.getBaseUrl(),
                                                            true))
                                                    .pushChangeHandler(new VerticalChangeHandler())
                                                    .popChangeHandler(new VerticalChangeHandler())));
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            }

                            swipeRefreshLayout.setRefreshing(false);
                            dispose(contactsQueryDisposable);
                        }
                        , () -> {
                            swipeRefreshLayout.setRefreshing(false);
                            dispose(contactsQueryDisposable);
                        });
    }

    private void prepareViews() {
        LinearLayoutManager layoutManager = new SmoothScrollLinearLayoutManager(getActivity());
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setHasFixedSize(true);
        recyclerView.setAdapter(adapter);

        recyclerView.addItemDecoration(new DividerItemDecoration(
                recyclerView.getContext(),
                layoutManager.getOrientation()
        ));

        swipeRefreshLayout.setOnRefreshListener(this::fetchData);
        swipeRefreshLayout.setColorSchemeResources(R.color.colorPrimary);
    }

    private void dispose(@Nullable Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        } else if (disposable == null) {

            if (contactsQueryDisposable != null && !contactsQueryDisposable.isDisposed()) {
                contactsQueryDisposable.dispose();
                contactsQueryDisposable = null;
            }

            if (cacheQueryDisposable != null && !cacheQueryDisposable.isDisposed()) {
                cacheQueryDisposable.dispose();
                cacheQueryDisposable = null;
            }
        }
    }

    @Override
    public void onSaveViewState(@NonNull View view, @NonNull Bundle outState) {
        super.onSaveViewState(view, outState);
        if (searchView != null && !TextUtils.isEmpty(searchView.getQuery())) {
            outState.putString(KEY_SEARCH_QUERY, searchView.getQuery().toString());
        }
    }

    @Override
    public void onRestoreViewState(@NonNull View view, @NonNull Bundle savedViewState) {
        super.onRestoreViewState(view, savedViewState);
        searchQuery = savedViewState.getString(KEY_SEARCH_QUERY, "");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dispose(null);
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        if (adapter.hasNewSearchText(newText) || !TextUtils.isEmpty(searchQuery)) {

            if (!TextUtils.isEmpty(searchQuery)) {
                adapter.setSearchText(searchQuery);
                searchQuery = "";
                adapter.filterItems();
            } else {
                adapter.setSearchText(newText);
                adapter.filterItems(300);
            }
        }

        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setEnabled(!adapter.hasSearchText());
        }

        return true;
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return onQueryTextChange(query);
    }

    @Override
    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
        adapter.setMode(SelectableAdapter.Mode.MULTI);
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode actionMode) {
        adapter.setMode(SelectableAdapter.Mode.IDLE);
        actionMode = null;
    }

    /*@Override
    public boolean onItemClick(int position) {
        if (actionMode != null && position != RecyclerView.NO_POSITION) {
            // Mark the position selected
            toggleSelection(position);
            return true;
        } else {
            // Handle the item click listener
            // We don't need to activate anything
            return false;
        }
    }*/

    private void toggleSelection(int position) {
        adapter.toggleSelection(position);

        int count = adapter.getSelectedItemCount();

        if (count == 0) {
            actionMode.finish();
        } else {
            //setContextTitle(count);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        adapter.onSaveInstanceState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (adapter != null) {
            adapter.onRestoreInstanceState(savedInstanceState);
        }
    }

    @Override
    public boolean onItemClick(int position) {
        if (contactItems.size() > position) {
            UserItem userItem = contactItems.get(position);
            RetrofitBucket retrofitBucket = ApiHelper.getRetrofitBucketForCreateRoom(userEntity.getBaseUrl(), "1",
                    userItem.getModel().getUserId());
            ncApi.createRoom(ApiHelper.getCredentials(userEntity.getUsername(), userEntity.getToken()),
                    retrofitBucket.getUrl(), retrofitBucket.getQueryMap())
                    .subscribeOn(Schedulers.newThread())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<RoomOverall>() {
                        @Override
                        public void onSubscribe(Disposable d) {

                        }

                        @Override
                        public void onNext(RoomOverall roomOverall) {
                            overridePushHandler(new NoOpControllerChangeHandler());
                            overridePopHandler(new NoOpControllerChangeHandler());
                            Intent callIntent = new Intent(getActivity(), CallActivity.class);
                            BundleBuilder bundleBuilder = new BundleBuilder(new Bundle());
                            bundleBuilder.putString("roomToken", roomOverall.getOcs().getData().getToken());
                            bundleBuilder.putParcelable("userEntity", Parcels.wrap(userEntity));
                            callIntent.putExtras(bundleBuilder.build());
                            startActivity(callIntent);
                        }

                        @Override
                        public void onError(Throwable e) {

                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }

        return true;
    }

}
