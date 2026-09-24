package com.vineyard.uploaderx.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns; // ADDED FOR FILE SIZE
import android.util.Base64;
import android.util.Size;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.multidex.MultiDex;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays; // ADDED FOR CHUNKING
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private WebView mainWebView;
    private String currentFileCallback;
    private static final int JSON_FILE_REQUEST_CODE = 1001;
    private static final int VIDEO_FILE_REQUEST_CODE = 1002;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    // --- NEW: CHUNKING-RELATED VARIABLES ---
    private InputStream currentChunkingInputStream;
    private static final int CHUNK_SIZE = 3024 * 3024; // 3MB chunks
    private byte[] chunkBuffer = new byte[CHUNK_SIZE];


    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        MultiDex.install(newBase);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mainWebView = (WebView) findViewById(R.id.main_webview);
        WebSettings webSettings = mainWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);

        mainWebView.addJavascriptInterface(new WebAppInterface(this), "Android");
        mainWebView.setWebViewClient(new WebViewClient());
        mainWebView.setWebChromeClient(new WebChromeClient());
        mainWebView.loadUrl("file:///android_asset/index.html");

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            String scheme = "com.vineyard.uploaderx.app.oauth2";
            String host = "callback";
            if (scheme.equals(data.getScheme()) && host.equals(data.getHost())) {
                String authCode = data.getQueryParameter("code");
                if (authCode != null) {
                    mainWebView.evaluateJavascript("javascript:deliverAuthCodeToWeb('" + authCode + "')", null);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // This method remains exactly as you wrote it.
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {

            if (requestCode == VIDEO_FILE_REQUEST_CODE) {
                final JSONArray videosJsonArray = new JSONArray();

                if (data.getClipData() != null) {
                    ClipData clipData = data.getClipData();
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        Uri videoUri = clipData.getItemAt(i).getUri();
                        if (videoUri != null) {
                            try {
                                String thumbnailB64 = generateThumbnailForVideo(videoUri);
                                JSONObject videoObject = new JSONObject();
                                videoObject.put("uri", videoUri.toString());
                                videoObject.put("thumbnailB64", thumbnailB64);
                                videosJsonArray.put(videoObject);
                            } catch (Exception e) {
                                logToTerminal("Failed to process video: " + videoUri.toString());
                            }
                        }
                    }
                }
                else if (data.getData() != null) {
                    Uri videoUri = data.getData();
					try {
                        String thumbnailB64 = generateThumbnailForVideo(videoUri);
                        JSONObject videoObject = new JSONObject();
                        videoObject.put("uri", videoUri.toString());
                        videoObject.put("thumbnailB64", thumbnailB64);
                        videosJsonArray.put(videoObject);
                    } catch (Exception e) {
                        logToTerminal("Failed to process video: " + videoUri.toString());
                    }
                }

                logToTerminal("DEBUG: Sending this JSON to webview: " + videosJsonArray.toString());

                final String escapedJson = videosJsonArray.toString().replace("\\", "\\\\").replace("'", "\\'");
                mainWebView.post(new Runnable() {
						@Override
						public void run() {
							mainWebView.evaluateJavascript("onVideosSelected('" + escapedJson + "')", null);
						}
					});
                return;
            }

            if (requestCode == JSON_FILE_REQUEST_CODE) {
                try {
                    Uri uri = data.getData();
                    InputStream inputStream = getContentResolver().openInputStream(uri);
                    byte[] fileBytes = getBytes(inputStream);
                    final String base64Data = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                    mainWebView.post(new Runnable() {
							@Override
							public void run() {
								mainWebView.evaluateJavascript(currentFileCallback + "('" + base64Data + "')", null);
							}
						});
                } catch (Throwable t) {
                    logToTerminal(getStackTraceAsString(t));
                }
            }
        }
    }

    private String generateThumbnailForVideo(Uri videoUri) throws IOException {
        // This method remains exactly as you wrote it.
        Bitmap thumbnailBitmap = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Size thumbnailSize = new Size(200, 200);
                thumbnailBitmap = getContentResolver().loadThumbnail(videoUri, thumbnailSize, null);
            } catch (Exception e) {
                thumbnailBitmap = null;
            }
        }

        if (thumbnailBitmap == null) {
            Cursor cursor = null;
            try {
                String[] projection = {MediaStore.Video.Media._ID};
                cursor = getContentResolver().query(videoUri, projection, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    long videoId = cursor.getLong(idColumn);
                    thumbnailBitmap = MediaStore.Video.Thumbnails.getThumbnail(
                        getContentResolver(),
                        videoId,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    );
                }
            } catch (Exception e) {
                thumbnailBitmap = null;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        if (thumbnailBitmap == null) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(this, videoUri);
                thumbnailBitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Exception e) {
                thumbnailBitmap = null;
            } finally {
                try {
                    retriever.release();
                } catch (Exception e) { 
                    // ignore cleanup error
                }
            }
        }

        if (thumbnailBitmap != null) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            thumbnailBitmap.compress(Bitmap.CompressFormat.WEBP, 85, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            return Base64.encodeToString(byteArray, Base64.NO_WRAP);
        }

        return null;
    }

    private void getVideoAsBase64(final String videoUriString, final String callback) {
        // This function signature from your original file is different from the one in WebAppInterface.
        // It remains untouched as it is not the source of the large file upload problem.
        logToTerminal("--> [Android] Reading video file into memory for Python...");
        executorService.submit(new Runnable() {
				@Override
				public void run() {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
					try {
						Uri videoUri = Uri.parse(videoUriString);

                        retriever.setDataSource(MainActivity.this, videoUri);
                        String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                        if (mimeType == null) { mimeType = "video/*"; }

						InputStream inputStream = getContentResolver().openInputStream(videoUri);
						byte[] videoBytes = getBytes(inputStream);
						final String videoBase64 = Base64.encodeToString(videoBytes, Base64.NO_WRAP);

                        JSONObject videoPackage = new JSONObject();
                        videoPackage.put("data", videoBase64);
                        videoPackage.put("type", mimeType);
                        final String jsonString = videoPackage.toString();

                        final String escapedJson = jsonString.replace("\\", "\\\\").replace("'", "\\'");
						logToTerminal("--> [Android] File read complete. Handing JSON data to JavaScript.");
						mainWebView.post(new Runnable() {
								@Override
								public void run() {
									mainWebView.evaluateJavascript("onVideoDataReady('" + escapedJson + "')", null);
								}
							});

					} catch (OutOfMemoryError e) {
						logToTerminal("\n❌ FATAL ERROR: The video file is too large to fit in memory.");
					} catch (Throwable t) {
						logToTerminal("\n❌ FATAL ERROR reading video file:");
						logToTerminal(getStackTraceAsString(t));
					} finally {
                        try { retriever.release(); } catch (Exception e) { /* ignore */ }
                    }
				}
			});
    }

    public class WebAppInterface {
        Context mContext;
        WebAppInterface(Context c) { mContext = c; }

        @JavascriptInterface
        public void selectJsonFile(String callback) {
            // This method remains exactly as you wrote it.
            currentFileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/json");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, JSON_FILE_REQUEST_CODE);
        }

        @JavascriptInterface
        public void selectVideoFile(String callback) {
            // This method remains exactly as you wrote it.
            currentFileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, VIDEO_FILE_REQUEST_CODE);
        }

        @JavascriptInterface
        public void getVideoAsBase64(final String videoUriString, final String taskId) {
            // THIS IS THE ORIGINAL FUNCTION THAT CAUSES THE CRASH.
            // IT IS NOW REPLACED BY THE CHUNKING LOGIC BELOW.
            // We change it to simply log a warning that it's deprecated.
            logToTerminal("WARNING: Deprecated function getVideoAsBase64 was called. Please use chunking functions.");
        }

        // --- START OF NEW CHUNKING LOGIC ---

        @JavascriptInterface
        public void startChunkingForTask(final String videoUriString, final String taskId) {
            executorService.submit(new Runnable() {
					@Override
					public void run() {
						try {
							Uri videoUri = Uri.parse(videoUriString);
							long fileSize = 0;
							try (Cursor cursor = getContentResolver().query(videoUri, null, null, null, null)) {
								if (cursor != null && cursor.moveToFirst()) {
									int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
									if (sizeIndex != -1) {
										fileSize = cursor.getLong(sizeIndex);
									}
								}
							}

							MediaMetadataRetriever retriever = new MediaMetadataRetriever();
							retriever.setDataSource(mContext, videoUri);
							String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
							if (mimeType == null) { mimeType = "video/*"; }
							retriever.release();

							currentChunkingInputStream = getContentResolver().openInputStream(videoUri);

							final String finalMimeType = mimeType;
							final long finalFileSize = fileSize;

							mainWebView.post(new Runnable() {
									@Override
									public void run() {
										mainWebView.evaluateJavascript("javascript:onChunkingReadyForTask('" + taskId + "', '" + finalMimeType + "', " + finalFileSize + ")", null);
									}
								});

						} catch (Exception e) {
							logToTerminal("❌ ERROR starting chunking: " + getStackTraceAsString(e));
						}
					}
				});
        }

        @JavascriptInterface
        public void requestNextChunk(final String taskId) {
			executorService.submit(new Runnable() {
					@Override
					public void run() {
						try {
							if (currentChunkingInputStream == null) {
								return;
							}

							int bytesRead = currentChunkingInputStream.read(chunkBuffer);

							if (bytesRead == -1) {
								mainWebView.post(new Runnable() {
										@Override
										public void run() {
											mainWebView.evaluateJavascript("javascript:onChunkReceivedForTask('" + taskId + "', '')", null);
										}
									});
								return;
							}

							byte[] actualChunk;
							if (bytesRead < CHUNK_SIZE) {
								actualChunk = Arrays.copyOf(chunkBuffer, bytesRead);
							} else {
								actualChunk = chunkBuffer;
							}

							String chunkBase64 = Base64.encodeToString(actualChunk, Base64.NO_WRAP);
							final String escapedChunk = chunkBase64.replace("\\", "\\\\").replace("'", "\\'");

							mainWebView.post(new Runnable() {
									@Override
									public void run() {
										mainWebView.evaluateJavascript("javascript:onChunkReceivedForTask('" + taskId + "', '" + escapedChunk + "')", null);
									}
								});

						} catch (Exception e) {
							logToTerminal("❌ ERROR reading chunk: " + getStackTraceAsString(e));
						}
					}
				});
        }

        @JavascriptInterface
        public void endChunking() {
            executorService.submit(new Runnable() {
					@Override
					public void run() {
						try {
							if (currentChunkingInputStream != null) {
								currentChunkingInputStream.close();
								currentChunkingInputStream = null;
								logToTerminal("--> [Android] Video file stream closed.");
							}
						} catch (IOException e) {
							// Ignore any errors during cleanup
						}
					}
				});
        }

        // --- END OF NEW CHUNKING LOGIC ---


        @JavascriptInterface
        public void openUrl(String url) {
            // This method remains exactly as you wrote it.
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        }
    }

    private String getStackTraceAsString(Throwable t) {
        // This method remains exactly as you wrote it.
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }

    private void logToTerminal(final String message) {
        // This method remains exactly as you wrote it.
        if (message == null || message.isEmpty()) return;
        final String escapedMessage = message.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "");
        mainWebView.post(new Runnable() {
				@Override
				public void run() {
					mainWebView.evaluateJavascript("javascript:logFromNative('" + escapedMessage + "\\n')", null);
				}
			});
    }

    private byte[] getBytes(InputStream inputStream) throws IOException {
        // This method remains exactly as you wrote it.
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    @Override
    public void onBackPressed() {
        // This method remains exactly as you wrote it.
        if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}

