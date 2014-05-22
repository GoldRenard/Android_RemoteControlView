package android.view;

import java.lang.ref.WeakReference;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.media.IRemoteControlDisplay;

public class RemoteControlView extends LinearLayout implements View.OnClickListener {

	private final String STR_UNKNOWN = " - ";
	private final String ENABLE_SETTINGS = "volumepanel_music";
	
	private final float IMAGE_SIZE_DIP = 110;
	private final float CONTROL_HEIGHT_DIP = 40;
	private Context mContext;
	private ImageView mImage;
	
	private TextView mSong;
	private TextView mArtist;
	private TextView mAlbum;
	
	private ImageButton mPrev;
	private ImageButton mStart;
	private ImageButton mNext;
	
	private Drawable mPreviousDrawable;
	private Drawable mPlayDrawable;
	private Drawable mPauseDrawable;
	private Drawable mNextDrawable;
	
	private int mClientGeneration;
	private PendingIntent mClientIntent;
	private int mCurrentPlayState;
	private long mStateTime;
	AudioManager mAudioManager;
	private Bitmap mArtwork;
	private boolean mAttached = false;
	private boolean mEnabled;
	private MotionEvent mEvent;
	
	public static final int MSG_SET_ARTWORK = 104;
	public static final int MSG_SET_GENERATION_ID = 103;
	public static final int MSG_SET_METADATA = 101;
	public static final int MSG_SET_TRANSPORT_CONTROLS = 102;
	public static final int MSG_UPDATE_STATE = 100;
	
	private IRemoteControlDisplayWeak mIRCD;
	
	/**
	 * Хендлер изменения состояния
	 */
    @SuppressLint("HandlerLeak")
	private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MSG_UPDATE_STATE:
            	if (mClientGeneration == msg.arg1) {
					updatePlayState(msg.arg2);
				}
                break;
            case MSG_SET_METADATA:
				if (mClientGeneration == msg.arg1) {
					updateMetadata((Bundle) msg.obj);
				}
                break;
            case MSG_SET_TRANSPORT_CONTROLS:
                break;
            case MSG_SET_ARTWORK:
                if (mClientGeneration == msg.arg1) {
                    if (mArtwork != null) {
                    	mArtwork.recycle();
                    }
                    mArtwork = (Bitmap)msg.obj;
            		mImage.setVisibility(mArtwork != null ? View.VISIBLE : View.GONE);
            		if (mArtwork != null) {
            			mImage.setImageBitmap(mArtwork);
            		}
                }
                break;

            case MSG_SET_GENERATION_ID:
                mClientGeneration = msg.arg1;
                mClientIntent = (PendingIntent) msg.obj;
                break;

            }
        }
    };
	
    /**
     * Интерфейс удаленного управления
     * @author Renard Gold (Илья Егоров)
     */
	private static class IRemoteControlDisplayWeak extends IRemoteControlDisplay.Stub {
		private WeakReference<Handler> mLocalHandler;

		IRemoteControlDisplayWeak(Handler handler) {
			mLocalHandler = new WeakReference<Handler>(handler);
		}

		public void setPlaybackState(int generationId, int state, long stateChangeTimeMs) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_UPDATE_STATE, generationId, state).sendToTarget();
			}
		}

		public void setMetadata(int generationId, Bundle metadata) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
			}
		}

		public void setTransportControlFlags(int generationId, int flags) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_TRANSPORT_CONTROLS, generationId, flags)
						.sendToTarget();
			}
		}

		public void setArtwork(int generationId, Bitmap bitmap) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
			}
		}

		public void setAllMetadata(int generationId, Bundle metadata, Bitmap bitmap) {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_METADATA, generationId, 0, metadata).sendToTarget();
				handler.obtainMessage(MSG_SET_ARTWORK, generationId, 0, bitmap).sendToTarget();
			}
		}

		public void setCurrentClientId(int clientGeneration, PendingIntent mediaIntent,
				boolean clearing) throws RemoteException {
			Handler handler = mLocalHandler.get();
			if (handler != null) {
				handler.obtainMessage(MSG_SET_GENERATION_ID,
					clientGeneration, (clearing ? 1 : 0), mediaIntent).sendToTarget();
			}
		}
	};
	
	/**
	 * Листенер изменения настройки
	 */
    private ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
        	setEnabled(Settings.System.getInt(mContext.getContentResolver(), ENABLE_SETTINGS, 1) == 1);
        }
    };
	
	public RemoteControlView(Context context) {
		super(context);
		init(context);
	}
	
	public RemoteControlView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}
	
	public RemoteControlView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init(context);
	}
	
	/**
	 * Инициализация всех элементов
	 * @param context Контекст
	 */
	private void init(Context context) {
		mContext = context;
		mCurrentPlayState = RemoteControlClient.PLAYSTATE_NONE;
		mContext.getContentResolver().registerContentObserver(Settings.System.getUriFor(ENABLE_SETTINGS), false, mSettingsObserver);
		mAudioManager = ((AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE));
		setEnabled(true);
		mIRCD = new IRemoteControlDisplayWeak(mHandler);
		
	    setOrientation(LinearLayout.HORIZONTAL);
	    setGravity(Gravity.TOP);
		
		mImage = new ImageView(mContext);
		mImage.setLayoutParams(new LinearLayout.LayoutParams(getDimension(IMAGE_SIZE_DIP), getDimension(IMAGE_SIZE_DIP)));
		mImage.setBackgroundColor(0xFF5C5C5C);
		mImage.setVisibility(View.GONE);
        addView(mImage);
        
        RelativeLayout mContainer = new RelativeLayout(mContext);
        mContainer.setLayoutParams(new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mContainer.addView(getInfoView());
        mContainer.addView(getDividerView());
        mContainer.addView(getControlsView());
        
        addView(mContainer);
	}
	
	/**
	 * Установка активности элемента
	 */
	@Override
	public void setEnabled(boolean isEnabled) {
		super.setEnabled(isEnabled);
		mEnabled = isEnabled;
		setVisibility(mEnabled ? View.VISIBLE : View.GONE);
	}
	
	/**
	 * При присоединении к окну - регистрируем управление
	 */
	@Override
	protected void onAttachedToWindow() {
	    super.onAttachedToWindow();
	    
	    if (!mEnabled) {
	    	return;
	    }
	    
	    if (!mAttached) {
	    	mAudioManager.registerRemoteControlDisplay(mIRCD);
	    }
	    
	    // При паузе даем 10 секунд на повторное отображение панели
	    if (mCurrentPlayState == RemoteControlClient.PLAYSTATE_PAUSED && SystemClock.elapsedRealtime() - mStateTime <= 10000) {
	    	setVisibility(View.VISIBLE);
	    } else {
	    	setVisibility(mAudioManager.isMusicActive() ? View.VISIBLE : View.GONE);
	    }

	    mAttached = true;
	}

	/**
	 * При отсоединении от окна - дерегистрируем управление
	 */
	@Override
	protected void onDetachedFromWindow() {
	    super.onDetachedFromWindow();
	    if (mAttached) {
		    mAudioManager.unregisterRemoteControlDisplay(mIRCD);
		    mHandler.removeMessages(MSG_SET_GENERATION_ID);
		    mHandler.removeMessages(MSG_SET_METADATA);
		    mHandler.removeMessages(MSG_SET_TRANSPORT_CONTROLS);
		    mHandler.removeMessages(MSG_UPDATE_STATE);
	    }
	    
	    mAttached = false;
	}
	
	/**
	 * Получение группы элементов информации о воспроизведении
	 * @return Группа элементов
	 */
	public View getInfoView() {
        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        
        RelativeLayout.LayoutParams lParams = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        int m = getDimension(5);
        lParams.setMargins(m, m, m, m);
        root.setLayoutParams(lParams);
        
        root.addView(getDividerView());
        
        mSong = newInfoTextView(android.R.style.TextAppearance_Medium);
        mSong.setText(STR_UNKNOWN);
        root.addView(mSong);
        
        mArtist = newInfoTextView(android.R.style.TextAppearance_Small);
        mArtist.setText(STR_UNKNOWN);
        root.addView(mArtist);
        
        mAlbum = newInfoTextView(android.R.style.TextAppearance_Small);
        mAlbum.setText(STR_UNKNOWN);
        root.addView(mAlbum);
        
        return root;
	}
	
	/**
	 * Получение элемента разделителя
	 * @return Разделитель
	 */
	public View getDividerView() {
        ImageView mDivider = new ImageView(mContext);
        RelativeLayout.LayoutParams lParams = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, getDimension(0.7f));
        lParams.setMargins(0, 0, 0, getDimension(CONTROL_HEIGHT_DIP));
        lParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        mDivider.setLayoutParams(lParams);
        mDivider.setScaleType(ScaleType.CENTER_CROP);
        mDivider.setImageDrawable(mContext.getResources().getDrawable(android.R.drawable.divider_horizontal_dark));
        return mDivider;
	}
	
	/**
	 * Инициализация группы элементов управления
	 * @return Группа элементов
	 */
	public View getControlsView() {
		
		mPreviousDrawable = mContext.getResources().getDrawable(android.R.drawable.ic_media_previous);
		mPlayDrawable = mContext.getResources().getDrawable(android.R.drawable.ic_media_play);
		mPauseDrawable = mContext.getResources().getDrawable(android.R.drawable.ic_media_pause);
		mNextDrawable = mContext.getResources().getDrawable(android.R.drawable.ic_media_next);
		
        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        RelativeLayout.LayoutParams lParams = new RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        root.setLayoutParams(lParams);
        
        root.setDividerDrawable(mContext.getResources().getDrawable(android.R.drawable.divider_horizontal_dark));
        root.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        root.setDividerPadding(getDimension(5));
		
        mPrev = newControlImageButton();
        mPrev.setImageDrawable(mPreviousDrawable);
        root.addView(mPrev);
        
        mStart = newControlImageButton();
        mStart.setImageDrawable(mPlayDrawable);
        root.addView(mStart);
        
        mNext = newControlImageButton();
        mNext.setImageDrawable(mNextDrawable);
        root.addView(mNext);
        
		return root;
	}
	
	/**
	 * Получение ImageButton;
	 * @return новый ImageButton
	 */
	public ImageButton newControlImageButton() {
		ImageButton mButton = new ImageButton(mContext, null, android.R.attr.borderlessButtonStyle);
		
		LinearLayout.LayoutParams lParams = new LinearLayout.LayoutParams(getDimension(0), getDimension(CONTROL_HEIGHT_DIP));
		lParams.weight = 1;
		mButton.setLayoutParams(lParams);
		int p = getDimension(6);
		mButton.setPadding(p, p, p, p);
		
		mButton.setCropToPadding(true);
		mButton.setScaleType(ScaleType.CENTER_INSIDE);
		mButton.setOnClickListener(this);
		mButton.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
            	// Используется для отправки ивента в родительский элемент
            	mEvent = event;
                return false;
            }
        });
		
		return mButton;
	}

	/**
	 * Получение TextView с нужным TextAppearance;
	 * @param textAppearance идентификатор стиля
	 * @return новый TextView
	 */
	public TextView newInfoTextView(int textAppearance) {
        TextView mText = new TextView(mContext);
        mText.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        mText.setSingleLine(true);
        mText.setEllipsize(TruncateAt.MARQUEE);
        mText.setSelected(true);
        mText.setTextAppearance(mContext, textAppearance);
        return mText;
	}
	
	/**
	 * Преобразование DIP значения в абсолютное
	 * @param dip DIP-Значение
	 * @return Абсолютное значение
	 */
	public int getDimension(float dip) {
		return (int)TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dip, mContext.getResources().getDisplayMetrics());
	}

	
	/**
	 * Обработчик кнопок
	 * @param v
	 */
    public void onClick(View v) {
        int keyCode = -1;
        if (v == mPrev) {
            keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        } else if (v == mNext) {
            keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
        } else if (v == mStart) {
            keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;

        }
        if (keyCode != -1) {
            sendMediaButtonClick(keyCode);
        }

        // Нам нужно отправить ивент нажатия обратно к VolumePanel, чтобы
        // тот сбросил таймаут
    	ViewParent parent = getParent();
    	if (parent != null && mEvent != null && parent instanceof LinearLayout) {
    		LinearLayout ll = (LinearLayout)parent;
	    	ll.dispatchTouchEvent(mEvent);
    	}
    }
	
	/**
	 * Отправка медиа-кнопки
	 * @param keyCode Код кнопки
	 */
    private void sendMediaButtonClick(int keyCode) {
        if (mClientIntent == null) {
            return;
        }
        KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        try {
            mClientIntent.send(getContext(), 0, intent);
        } catch (CanceledException e) {
            e.printStackTrace();
        }

        keyEvent = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
        intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
        try {
            mClientIntent.send(getContext(), 0, intent);
        } catch (CanceledException e) {
            e.printStackTrace();
        }
    }
    
	/* =========================================
	 * 			RemoteDisplaySection
	 * =========================================*/
	
	/**
	 * Установка кнопки плей/паузы
	 * @param state Состояние
	 */
	private void updatePlayState(int state) {
		
        if (state == mCurrentPlayState) {
            return;
        }
		
		if (state == RemoteControlClient.PLAYSTATE_PLAYING && mStart != null) {
			mStart.setImageDrawable(mPauseDrawable);
		} else {
			mStart.setImageDrawable(mPlayDrawable);
		}
		mCurrentPlayState = state;
		mStateTime = SystemClock.elapsedRealtime();
	}
	
	/**
	 * Обновление мета-данных
	 * @param data Бандл с данными
	 */
	private void updateMetadata(Bundle data) {
		
		String artist = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST);
		if (artist == null) {
			artist = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ARTIST);
		}
		if (artist == null) {
			artist = STR_UNKNOWN;
		}
		
		String title = getMdString(data, MediaMetadataRetriever.METADATA_KEY_TITLE);
		if (title == null) {
			title = STR_UNKNOWN;
		}

		String album = getMdString(data, MediaMetadataRetriever.METADATA_KEY_ALBUM);
		if (album == null) {
			album = STR_UNKNOWN;
		}
		
		if ((artist != null) && (title != null)) {
			mArtist.setText(artist);
			mSong.setText(title);
			mAlbum.setText(album);
		}
	}

	private String getMdString(Bundle data, int id) {
		return data.getString(Integer.toString(id));
	}
}
