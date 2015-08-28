package com.malmstein.yahnac.data;

import android.content.ContentValues;
import android.text.TextUtils;
import android.util.Pair;

import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.ValueEventListener;
import com.malmstein.yahnac.comments.CommentsParser;
import com.malmstein.yahnac.comments.VoteUrlParser;
import com.malmstein.yahnac.inject.Inject;
import com.malmstein.yahnac.model.Login;
import com.malmstein.yahnac.model.OperationResponse;
import com.malmstein.yahnac.model.Story;
import com.novoda.notils.logger.simple.Log;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.jsoup.Connection;
import org.jsoup.nodes.Document;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

public class HNewsApi {

    private static final String BAD_UPVOTE_RESPONSE = "Can't make that vote.";

    public Observable<List<ContentValues>> getStories(final Story.FILTER FILTER) {

        return Observable.create(new Observable.OnSubscribe<DataSnapshot>() {
            @Override
            public void call(final Subscriber<? super DataSnapshot> subscriber) {
                Firebase topStories = getStoryFirebase(FILTER);
                topStories.addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (dataSnapshot != null) {
                            subscriber.onNext(dataSnapshot);
                        } else {
                            Inject.crashAnalytics().logSomethingWentWrong("HNewsApi: getStories is empty for " + FILTER.name());
                        }
                        subscriber.onCompleted();
                    }

                    @Override
                    public void onCancelled(FirebaseError firebaseError) {
                        Log.d(firebaseError.getCode());
                    }
                });
            }
        }).flatMap(new Func1<DataSnapshot, Observable<Pair<Integer, Long>>>() {
            @Override
            public Observable<Pair<Integer, Long>> call(final DataSnapshot dataSnapshot) {
                return Observable.create(new Observable.OnSubscribe<Pair<Integer, Long>>() {
                    @Override
                    public void call(Subscriber<? super Pair<Integer, Long>> subscriber) {
                        for (int i = 0; i < dataSnapshot.getChildrenCount(); i++) {
                            Long id = (Long) dataSnapshot.child(String.valueOf(i)).getValue();
                            Integer rank = Integer.valueOf(dataSnapshot.child(String.valueOf(i)).getKey());
                            Pair<Integer, Long> storyRoot = new Pair<>(rank, id);
                            subscriber.onNext(storyRoot);
                        }
                        subscriber.onCompleted();
                    }
                });
            }
        }).flatMap(new Func1<Pair<Integer, Long>, Observable<ContentValues>>() {
            @Override
            public Observable<ContentValues> call(final Pair<Integer, Long> storyRoot) {
                return Observable.create(new Observable.OnSubscribe<ContentValues>() {
                    @Override
                    public void call(final Subscriber<? super ContentValues> subscriber) {
                        final Firebase story = new Firebase("https://hacker-news.firebaseio.com/v0/item/" + storyRoot.second);
                        story.addValueEventListener(new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                Map<String, Object> newItem = (Map<String, Object>) dataSnapshot.getValue();
                                if (newItem != null) {
                                    ContentValues story = mapStory(newItem, FILTER, storyRoot.first);
                                    if (story != null) {
                                        subscriber.onNext(story);
                                    } else {
                                        subscriber.onNext(new ContentValues());
                                        Inject.crashAnalytics().logSomethingWentWrong("HNewsApi: onDataChange is empty in " + storyRoot.second);
                                    }
                                }
                                subscriber.onCompleted();
                            }

                            @Override
                            public void onCancelled(FirebaseError firebaseError) {
                                Log.d(firebaseError.getCode());
                                Inject.crashAnalytics().logSomethingWentWrong("HNewsApi: onCancelled " + firebaseError.getMessage());
                                subscriber.onCompleted();
                            }
                        });
                    }
                });
            }
        })
                .toList();
    }

    private ContentValues mapStory(Map<String, Object> map, Story.FILTER filter, Integer rank) {

        ContentValues storyValues = new ContentValues();

        try {
            String by = (String) map.get("by");
            Long id = (Long) map.get("id");
            String type = (String) map.get("type");
            Long time = (Long) map.get("time");
            Long score = (Long) map.get("score");
            String title = (String) map.get("title");
            String url = (String) map.get("url");
            Long descendants = Long.valueOf(0);
            if (map.get("descendants") != null) {
                descendants = (Long) map.get("descendants");
            }

            storyValues.put(HNewsContract.StoryEntry.ITEM_ID, id);
            storyValues.put(HNewsContract.StoryEntry.BY, by);
            storyValues.put(HNewsContract.StoryEntry.TYPE, type);
            storyValues.put(HNewsContract.StoryEntry.TIME_AGO, time * 1000);
            storyValues.put(HNewsContract.StoryEntry.SCORE, score);
            storyValues.put(HNewsContract.StoryEntry.TITLE, title);
            storyValues.put(HNewsContract.StoryEntry.COMMENTS, descendants);
            storyValues.put(HNewsContract.StoryEntry.URL, url);
            storyValues.put(HNewsContract.StoryEntry.RANK, rank);
            storyValues.put(HNewsContract.StoryEntry.TIMESTAMP, System.currentTimeMillis());
            storyValues.put(HNewsContract.StoryEntry.FILTER, filter.name());
        } catch (Exception ex) {
            Log.d(ex.getMessage());
        }

        return storyValues;
    }

    private Firebase getStoryFirebase(Story.FILTER FILTER) {
        switch (FILTER) {
            case show:
                return new Firebase("https://hacker-news.firebaseio.com/v0/showstories");
            case ask:
                return new Firebase("https://hacker-news.firebaseio.com/v0/askstories");
            case jobs:
                return new Firebase("https://hacker-news.firebaseio.com/v0/jobstories");
            default:
                return new Firebase("https://hacker-news.firebaseio.com/v0/topstories");
        }
    }

    Observable<Vector<ContentValues>> getCommentsFromStory(Long storyId) {
        return Observable.create(
                new CommentsUpdateOnSubscribe(storyId))
                .subscribeOn(Schedulers.io());
    }

    Observable<Login> login(String username, String password) {
        return Observable.create(
                new LoginOnSubscribe(username, password))
                .subscribeOn(Schedulers.io());
    }

    Observable<OperationResponse> vote(Story storyId) {
        return Observable.create(
                new ParseVoteUrlOnSubscribe(storyId.getId()))
                .flatMap(new Func1<String, Observable<OperationResponse>>() {
                    @Override
                    public Observable<OperationResponse> call(final String voteUrl) {
                        return Observable.create(new Observable.OnSubscribe<OperationResponse>() {
                            @Override
                            public void call(Subscriber<? super OperationResponse> subscriber) {

                                if (voteUrl.equals(VoteUrlParser.EMPTY)) {
                                    subscriber.onNext(OperationResponse.FAILURE);
                                }

                                try {
                                    ConnectionProvider connectionProvider = Inject.connectionProvider();
                                    Connection.Response response = connectionProvider
                                            .voteConnection(voteUrl)
                                            .execute();

                                    if (response.statusCode() == 200) {
                                        if (response.body() == null) {
                                            subscriber.onError(new Throwable(""));
                                        }

                                        Document doc = response.parse();
                                        String text = doc.text();

                                        if (text.equals(BAD_UPVOTE_RESPONSE)) {
                                            subscriber.onNext(OperationResponse.FAILURE);
                                        } else {
                                            subscriber.onNext(OperationResponse.SUCCESS);
                                        }
                                    } else {
                                        subscriber.onNext(OperationResponse.FAILURE);
                                    }

                                } catch (IOException e) {
                                    subscriber.onError(e);
                                }

                            }
                        });
                    }
                })
                .subscribeOn(Schedulers.io());
    }

    Observable<OperationResponse> reply(Long itemId, final String comment) {
        return Observable.create(
                new ParseReplyUrlOnSubscribe(itemId))
                .flatMap(new Func1<String, Observable<OperationResponse>>() {
                    @Override
                    public Observable<OperationResponse> call(final String fnid) {
                        return Observable.create(new Observable.OnSubscribe<OperationResponse>() {
                            @Override
                            public void call(Subscriber<? super OperationResponse> subscriber) {

                                try {
                                    ConnectionProvider connectionProvider = Inject.connectionProvider();
                                    Connection.Response response = connectionProvider
                                            .replyConnection(fnid, comment)
                                            .execute();

                                    if (response.statusCode() == 200) {
                                        if (response.body() == null) {
                                            subscriber.onError(new Throwable(""));
                                        }

                                        Document doc = response.parse();
                                        String text = doc.text();

                                        if (text.equals(BAD_UPVOTE_RESPONSE)) {
                                            subscriber.onNext(OperationResponse.FAILURE);
                                        } else {
                                            subscriber.onNext(OperationResponse.SUCCESS);
                                        }
                                    } else {
                                        subscriber.onNext(OperationResponse.FAILURE);
                                    }

                                } catch (IOException e) {
                                    subscriber.onError(e);
                                }

                            }
                        });
                    }
                })
                .subscribeOn(Schedulers.io());
    }

    private static class CommentsUpdateOnSubscribe implements Observable.OnSubscribe<Vector<ContentValues>> {

        private final Long storyId;
        private Subscriber<? super Vector<ContentValues>> subscriber;

        private CommentsUpdateOnSubscribe(Long storyId) {
            this.storyId = storyId;
        }

        @Override
        public void call(Subscriber<? super Vector<ContentValues>> subscriber) {
            this.subscriber = subscriber;
            startFetchingComments();
            subscriber.onCompleted();
        }

        private void startFetchingComments() {
            Vector<ContentValues> commentsList = new Vector<>();
            try {
                ConnectionProvider connectionProvider = Inject.connectionProvider();
                Document commentsDocument = connectionProvider
                        .commentsConnection(storyId)
                        .get();

                commentsList = new CommentsParser(storyId, commentsDocument).parse();

            } catch (IOException e) {
                subscriber.onError(e);
            }
            subscriber.onNext(commentsList);
        }
    }

    private static class LoginOnSubscribe implements Observable.OnSubscribe<Login> {

        private final String username;
        private final String password;
        private Subscriber<? super Login> subscriber;

        private LoginOnSubscribe(String username, String password) {
            this.username = username;
            this.password = password;
        }

        @Override
        public void call(Subscriber<? super Login> subscriber) {
            this.subscriber = subscriber;
            attemptLogin();
            subscriber.onCompleted();
        }

        private void attemptLogin() {
            try {
                ConnectionProvider connectionProvider = Inject.connectionProvider();
                Connection.Response response = connectionProvider
                        .loginConnection(username, password)
                        .execute();

                String cookie = response.cookie("user");
                String cfduid = response.cookie("_cfduid");

                if (!TextUtils.isEmpty(cookie)) {
                    subscriber.onNext(new Login(username, cookie, Login.Status.SUCCESSFUL));
                } else {
                    subscriber.onNext(new Login(username, null, Login.Status.WRONG_CREDENTIALS));
                }

            } catch (IOException e) {
                subscriber.onError(e);
            }
        }
    }

    private static class ParseVoteUrlOnSubscribe implements Observable.OnSubscribe<String> {

        private final Long storyId;
        private Subscriber<? super String> subscriber;

        private ParseVoteUrlOnSubscribe(Long storyId) {
            this.storyId = storyId;
        }

        @Override
        public void call(Subscriber<? super String> subscriber) {
            this.subscriber = subscriber;
            startFetchingVoteUrl();
            subscriber.onCompleted();
        }

        private void startFetchingVoteUrl() {
            try {
                ConnectionProvider connectionProvider = Inject.connectionProvider();
                Document commentsDocument = connectionProvider
                        .commentsConnection(storyId)
                        .get();

                String voteUrl = new VoteUrlParser(commentsDocument, storyId).parse();
                subscriber.onNext(voteUrl);
            } catch (IOException e) {
                subscriber.onError(e);
            }
        }
    }

    private static class ParseReplyUrlOnSubscribe implements Observable.OnSubscribe<String> {

        private final Long storyId;
        private Subscriber<? super String> subscriber;

        private ParseReplyUrlOnSubscribe(Long storyId) {
            this.storyId = storyId;
        }

        @Override
        public void call(Subscriber<? super String> subscriber) {
            this.subscriber = subscriber;
            startFetchingReplyUrl();
            subscriber.onCompleted();
        }

        private void startFetchingReplyUrl() {
            try {
                ConnectionProvider connectionProvider = Inject.connectionProvider();
                String replyUrl = connectionProvider
                        .commentsConnection(storyId)
                        .get()
                        .select("input[name=fnid]")
                        .first()
                        .attr("value");

                subscriber.onNext(replyUrl);
            } catch (IOException e) {
                subscriber.onError(e);
            }
        }
    }
}
