/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.android.mediabrowserservice;

import android.app.Fragment;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.view.View.OnClickListener;

import com.example.android.mediabrowserservice.utils.LogHelper;
import com.musixmatch.lyrics.MissingPluginException;
import com.musixmatch.lyrics.musiXmatchLyricsConnector;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * A class that shows the Media Queue to the user.
 */
public class QueueFragment extends Fragment {

    private static final String TAG = LogHelper.makeLogTag(QueueFragment.class.getSimpleName());

    private musiXmatchLyricsConnector mLyricsPlugin = null;
    private ImageButton mSkipNext;
    private ImageButton mSkipPrevious;
    private ImageButton mPlayPause;

    private MediaBrowser mMediaBrowser;
    private MediaController.TransportControls mTransportControls;
    private MediaController mMediaController;
    private PlaybackState mPlaybackState;
    AssetManager mngr;
    private View rootView;
    private QueueAdapter mQueueAdapter;

    private MediaBrowser.ConnectionCallback mConnectionCallback =
            new MediaBrowser.ConnectionCallback() {
        @Override
        public void onConnected() {
            LogHelper.d(TAG, "onConnected: session token ", mMediaBrowser.getSessionToken());

            if (mMediaBrowser.getSessionToken() == null) {
                throw new IllegalArgumentException("No Session token");
            }

            mMediaController = new MediaController(getActivity(),
                    mMediaBrowser.getSessionToken());
            mTransportControls = mMediaController.getTransportControls();
            mMediaController.registerCallback(mSessionCallback);

            getActivity().setMediaController(mMediaController);
            mPlaybackState = mMediaController.getPlaybackState();

            List<MediaSession.QueueItem> queue = mMediaController.getQueue();
            if (queue != null) {
                mQueueAdapter.clear();
                mQueueAdapter.notifyDataSetInvalidated();
                mQueueAdapter.addAll(queue);
                mQueueAdapter.notifyDataSetChanged();
            }
            onPlaybackStateChanged(mPlaybackState);
        }

        @Override
        public void onConnectionFailed() {
            LogHelper.d(TAG, "onConnectionFailed");
        }

        @Override
        public void onConnectionSuspended() {
            LogHelper.d(TAG, "onConnectionSuspended");
            mMediaController.unregisterCallback(mSessionCallback);
            mTransportControls = null;
            mMediaController = null;
            getActivity().setMediaController(null);
        }
    };

    // Receive callbacks from the MediaController. Here we update our state such as which queue
    // is being shown, the current title and description and the PlaybackState.
    private MediaController.Callback mSessionCallback = new MediaController.Callback() {

        @Override
        public void onSessionDestroyed() {
            LogHelper.d(TAG, "Session destroyed. Need to fetch a new Media Session");
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (state == null) {
                return;
            }
            LogHelper.d(TAG, "Received playback state change to state ", state.getState());
            mPlaybackState = state;
            QueueFragment.this.onPlaybackStateChanged(state);
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            LogHelper.d(TAG, "onQueueChanged ", queue);
            if (queue != null) {
                mQueueAdapter.clear();
                mQueueAdapter.notifyDataSetInvalidated();
                mQueueAdapter.addAll(queue);
                mQueueAdapter.notifyDataSetChanged();
            }
        }
    };

    public static QueueFragment newInstance() {
        return new QueueFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.now_playing, container, false);
        mngr = getActivity().getAssets();
        mSkipPrevious = (ImageButton) rootView.findViewById(R.id.skip_previous);
        mSkipPrevious.setEnabled(false);
        mSkipPrevious.setOnClickListener(mButtonListener);

        mSkipNext = (ImageButton) rootView.findViewById(R.id.skip_next);
        mSkipNext.setEnabled(false);
        mSkipNext.setOnClickListener(mButtonListener);

        mPlayPause = (ImageButton) rootView.findViewById(R.id.play_pause);
        mPlayPause.setEnabled(true);
        mPlayPause.setOnClickListener(mButtonListener);

        mQueueAdapter = new QueueAdapter(getActivity());

        Button all_songs_btn = (Button) rootView.findViewById(R.id.all_songs_btn);
            all_songs_btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //open the music player
                    getFragmentManager().beginTransaction()
                            .replace(R.id.container, BrowseFragment.newInstance("__BY_GENRE__/Rock"))
                            .addToBackStack(null)
                            .commit();
                }
            });
        Button playlist_btn = (Button) rootView.findViewById(R.id.playlist_btn);
        playlist_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //open the music player
                getFragmentManager().beginTransaction()
                        .replace(R.id.container, BrowseFragment.newInstance(null))
                        .addToBackStack(null)
                        .commit();
            }
        });

        mMediaBrowser = new MediaBrowser(getActivity(),
                new ComponentName(getActivity(), MusicService.class),
                mConnectionCallback, null);


        mLyricsPlugin = new musiXmatchLyricsConnector(getActivity());
        mLyricsPlugin.setLoadingMessage("Loading", "Music wizard is loading lyrics for your song..");

        rootView.findViewById(R.id.lyrics_image).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    TextView song_name = (TextView) rootView.findViewById(R.id.song_name);
                    final String trackName = (String) song_name.getText();

                    TextView artist_name = (TextView) rootView.findViewById(R.id.artist_name);
                    final String artistName = (String) artist_name.getText();
                    mLyricsPlugin.startLyricsActivity(artistName, trackName);
                } catch (MissingPluginException e) {
                    mLyricsPlugin.downloadLyricsPlugin();
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });

        //facebook search
        //https://www.facebook.com/search/results/?init=quick&q=asd&tas=0.8884490253403783
        rootView.findViewById(R.id.facebook_image).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    TextView song_name = (TextView) rootView.findViewById(R.id.song_name);
                    final String trackName = (String) song_name.getText();

                    TextView artist_name = (TextView) rootView.findViewById(R.id.artist_name);
                    final String artistName = (String) artist_name.getText();

                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com/search/results/?init=quick&q="+trackName+"+"+artistName));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        //youtube search
        //https://www.youtube.com/results?search_query=asd&page=&utm_source=opensearch
        rootView.findViewById(R.id.youtube_search).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    TextView song_name = (TextView) rootView.findViewById(R.id.song_name);
                    final String trackName = (String) song_name.getText();

                    TextView artist_name = (TextView) rootView.findViewById(R.id.artist_name);
                    final String artistName = (String) artist_name.getText();

                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query="+trackName+"+"+artistName));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        //guitar lyrics
        //http://www.chordie.com/?q=asd&np=0&ps=10&wf=2221&s=RPD&wf=2221&wm=wrd&type=&sp=1&sy=1&cat=&ul=&np=0
        rootView.findViewById(R.id.guitar_lyrics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    TextView song_name = (TextView) rootView.findViewById(R.id.song_name);
                    final String trackName = (String) song_name.getText();

                    TextView artist_name = (TextView) rootView.findViewById(R.id.artist_name);
                    final String artistName = (String) artist_name.getText();

                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.chordie.com/?q="+trackName+"+"+artistName+"&np=0&ps=10&wf=2221&s=RPD&wf=2221&wm=wrd&type=&sp=1&sy=1&cat=&ul=&np=0"));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mMediaBrowser != null) {
            mLyricsPlugin.doBindService();
            mMediaBrowser.connect();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mLyricsPlugin.doUnbindService();
        if (mMediaController != null) {
            mMediaController.unregisterCallback(mSessionCallback);
        }
        if (mMediaBrowser != null) {
            mMediaBrowser.disconnect();
        }
    }


    private void onPlaybackStateChanged(PlaybackState state) {
        LogHelper.d(TAG, "onPlaybackStateChanged ", state);
        if (state == null) {
            return;
        }
        mQueueAdapter.setActiveQueueItemId(state.getActiveQueueItemId());
        mQueueAdapter.notifyDataSetChanged();
        boolean enablePlay = false;
        StringBuilder statusBuilder = new StringBuilder();
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
                statusBuilder.append("playing");
                enablePlay = false;
                updateNowPlaying();
                break;
            case PlaybackState.STATE_PAUSED:
                statusBuilder.append("paused");
                enablePlay = true;
                break;
            case PlaybackState.STATE_STOPPED:
                statusBuilder.append("ended");
                enablePlay = true;
                break;
            case PlaybackState.STATE_ERROR:
                statusBuilder.append("error: ").append(state.getErrorMessage());
                break;
            case PlaybackState.STATE_BUFFERING:
                statusBuilder.append("buffering");
                break;
            case PlaybackState.STATE_NONE:
                statusBuilder.append("none");
                enablePlay = false;
                break;
            case PlaybackState.STATE_CONNECTING:
                statusBuilder.append("connecting");
                break;
            default:
                statusBuilder.append(mPlaybackState);
        }
        statusBuilder.append(" -- At position: ").append(state.getPosition());
        LogHelper.d(TAG, statusBuilder.toString());

        if (enablePlay) {
            mPlayPause.setImageDrawable(
                    getActivity().getDrawable(R.drawable.ic_play_arrow_white_24dp));
        } else {
            mPlayPause.setImageDrawable(getActivity().getDrawable(R.drawable.ic_pause_white_24dp));
        }

        mSkipPrevious.setEnabled((state.getActions() & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        mSkipNext.setEnabled((state.getActions() & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);

        LogHelper.d(TAG, "Queue From MediaController *** Title " +
                mMediaController.getQueueTitle() + "\n: Queue: " + mMediaController.getQueue() +
                "\n Metadata " + mMediaController.getMetadata());
    }

    private View.OnClickListener mButtonListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            final int state = mPlaybackState == null ?
                    PlaybackState.STATE_NONE : mPlaybackState.getState();
            switch (v.getId()) {
                case R.id.play_pause:
                    LogHelper.d(TAG, "Play button pressed, in state " + state);
                    if (state == PlaybackState.STATE_PAUSED ||
                            state == PlaybackState.STATE_STOPPED ||
                            state == PlaybackState.STATE_NONE) {
                        playMedia();

                    } else if (state == PlaybackState.STATE_PLAYING) {
                        pauseMedia();
                    }
                    break;
                case R.id.skip_previous:
                    LogHelper.d(TAG, "Start button pressed, in state " + state);
                    skipToPrevious();
                    break;
                case R.id.skip_next:
                    skipToNext();
                    break;
            }
        }
    };

    private void playMedia() {
        if (mTransportControls != null) {
            mTransportControls.play();
        }
    }

    private void pauseMedia() {
        if (mTransportControls != null) {
            mTransportControls.pause();
        }
    }

    private void skipToPrevious() {
        if (mTransportControls != null) {
            mTransportControls.skipToPrevious();
        }
    }

    private void skipToNext() {
        if (mTransportControls != null) {
            mTransportControls.skipToNext();
        }
    }

    private void updateNowPlaying(){
        MediaMetadata mMetadata = mMediaController.getMetadata();
        MediaDescription song = mMetadata.getDescription();
        TextView song_name = (TextView) rootView.findViewById(R.id.song_name);
        song_name.setText(song.getTitle());
        TextView artist_name = (TextView) rootView.findViewById(R.id.artist_name);
        artist_name.setText(song.getSubtitle());

        Bitmap art = null;
        if (song.getIconUri() != null) {
            // This sample assumes the iconUri will be a valid URL formatted String, but
            // it can actually be any valid Android Uri formatted String.
            // async fetch the album art icon
            ImageView album_art = (ImageView) rootView.findViewById(R.id.album_art);
            final String CATALOG_URL =
                    "http://storage.googleapis.com/automotive-media/music.json";
            String artUrl = song.getIconUri().toString();

            //check local
            try {
                InputStream ims = mngr.open(artUrl);
                // load image
                // get input stream
                // load image as Drawable
                Drawable d = Drawable.createFromStream(ims, null);
                // set image to ImageView
                album_art.setImageDrawable(d);
            } catch (IOException ex) {
                ex.printStackTrace();

                int slashPos = CATALOG_URL.lastIndexOf('/');
                String basePath = CATALOG_URL.substring(0, slashPos + 1);
                art = AlbumArtCache.getInstance().getBigImage(basePath+artUrl);
                if(art != null) {
                    album_art.setImageBitmap(art);
                    album_art.setBackground(null);
                }
            }

        }

    }
    private void readFromUrl(String artistName, String trackName){
        /** Create a new textview array to display the results */
        TextView name[];
        TextView website[];
        TextView category[];

        try {

            URL url = new URL("http://api.chartlyrics.com/apiv1.asmx/SearchLyric?artist="+artistName+"&song="+trackName);
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(url.openStream()));
            doc.getDocumentElement().normalize();

            NodeList nodeList = doc.getElementsByTagName("SearchLyricResult");

            /** Assign textview array lenght by arraylist size */
            name = new TextView[nodeList.getLength()];
            website = new TextView[nodeList.getLength()];
            category = new TextView[nodeList.getLength()];

            ListView listView = (ListView) rootView.findViewById(R.id.list_view);
            //LIST OF ARRAY STRINGS WHICH WILL SERVE AS LIST ITEMS
            ArrayList<String> listItems=new ArrayList<String>();

            //DEFINING A STRING ADAPTER WHICH WILL HANDLE THE DATA OF THE LISTVIEW
            ArrayAdapter<String> adapter;
            adapter=new ArrayAdapter<String>(getActivity(),android.R.layout.simple_list_item_1,listItems);

            for (int i = 0; i < nodeList.getLength(); i++) {

                Node node = nodeList.item(i);

                name[i] = new TextView(getActivity());
                website[i] = new TextView(getActivity());
                category[i] = new TextView(getActivity());

                Element fstElmnt = (Element) node;
                NodeList nameList = fstElmnt.getElementsByTagName("Song");
                Element nameElement = (Element) nameList.item(0);
                nameList = nameElement.getChildNodes();
                name[i].setText("Song = " + ((Node) nameList.item(0)).getNodeValue());

                NodeList websiteList = fstElmnt.getElementsByTagName("Artist");
                Element websiteElement = (Element) websiteList.item(0);
                websiteList = websiteElement.getChildNodes();
                website[i].setText("Artist = " + ((Node) websiteList.item(0)).getNodeValue());

                int clickCounter=0;
                listItems.add("Clicked : "+clickCounter++);
            }
            //RECORDING HOW MANY TIMES THE BUTTON HAS BEEN CLICKED
            listView.setAdapter(adapter);


        } catch (Exception e) {
            System.out.println("XML Pasing Excpetion = " + e);
        }
    }


}
