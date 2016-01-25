package com.kickstarter.services;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.kickstarter.R;
import com.kickstarter.libs.FormContents;
import com.kickstarter.libs.utils.IOUtils;
import com.kickstarter.ui.IntentKey;
import com.kickstarter.ui.activities.ProjectActivity;
import com.kickstarter.ui.activities.WebViewActivity;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KSWebViewClient extends WebViewClient {

  public interface Delegate {
    void webViewOnPageStarted(final @NonNull KSWebViewClient webViewClient, @Nullable final String url);
    void webViewOnPageFinished(final @NonNull KSWebViewClient webViewClient, @Nullable final String url);
    void webViewPageIntercepted(final @NonNull KSWebViewClient webViewClient, final @NonNull String url);
  }

  private boolean initialPageLoad = true;
  private final OkHttpClient client;
  private final String webEndpoint;
  private final List<RequestHandler> requestHandlers = new ArrayList<>();
  private FormContents formContents = null;
  private @Nullable Delegate delegate;

  public KSWebViewClient(final @NonNull OkHttpClient client, final @NonNull String webEndpoint) {
    this(client, webEndpoint, null);
  }

  public KSWebViewClient(final @NonNull OkHttpClient client, final @NonNull String webEndpoint,
    final @Nullable Delegate delegate) {

    this.client = client;
    this.webEndpoint = webEndpoint;
    this.delegate = delegate;

    initializeRequestHandlers();
  }

  public void setDelegate(final @Nullable Delegate delegate) {
    this.delegate = delegate;
  }

  public @Nullable Delegate delegate() {
    return delegate;
  }

  @Override
  public void onPageStarted(final @Nullable WebView view, final @Nullable String url, final @Nullable Bitmap favicon) {
    if (delegate != null) {
      delegate.webViewOnPageStarted(this, url);
    }
  }

  @Override
  public void onPageFinished(@NonNull final WebView view, @NonNull final String url) {
    if (delegate != null) {
      delegate.webViewOnPageFinished(this, url);
    }
    initialPageLoad = false;
  }

  @Override
  public WebResourceResponse shouldInterceptRequest(@NonNull final WebView view, @NonNull final String url) {
    if (!isInterceptable(Uri.parse(url))) {
      return null;
    }

    try {
      final Request request = buildRequest(url);

      if (handleRequest(request, view)) {
        return noopWebResourceResponse();
      }

      final Response response = client.newCall(request).execute();

      // response.request() may be different to the initial request. e.g.: If a logged out user tries to pledge,
      // the backend will respond with a redirect to login - response.request().url() would contain a login URL,
      // not a pledge URL.
      if (!request.equals(response.request()) && handleRequest(response.request(), view)) {
        return noopWebResourceResponse();
      }

      final MimeHeaders mimeHeaders = new MimeHeaders(response.body().contentType().toString());
      final InputStream body = constructBody(view.getContext(), response, mimeHeaders);

      if (mimeHeaders.type != null && mimeHeaders.type.equals("text/html")) {
        webViewPageIntercepted(response.request().urlString());
      }

      return new WebResourceResponse(mimeHeaders.type, mimeHeaders.encoding, body);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      formContents = null; // TODO: Should unset this much earlier?
    }
  }

  // The order of request handlers is important - we iterate through the request handlers
  // sequentially until a match is found.
  public void registerRequestHandlers(@NonNull final List<RequestHandler> requestHandlers) {
    this.requestHandlers.addAll(0, requestHandlers);
  }

  public void setFormContents(@NonNull final FormContents formContents) {
    this.formContents = formContents;
  }

  protected InputStream constructBody(@NonNull final Context context, @NonNull final Response response,
    @NonNull final MimeHeaders mimeHeaders) throws IOException {
    InputStream body = response.body().byteStream();

    if (mimeHeaders.type != null && mimeHeaders.type.equals("text/html")) {
      body = insertWebViewJavascript(context, body);
    }

    return body;
  }

  protected Request buildRequest(@NonNull final String url) {
    final Request.Builder requestBuilder = new Request.Builder().url(url);

    RequestBody requestBody = null;
    if (httpMethod().equals("POST")) {
      requestBody = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"),
        formContents.serialized);
    }

    requestBuilder.method(httpMethod(), requestBody);

    return requestBuilder.build();
  }

  protected InputStream insertWebViewJavascript(@NonNull final Context context, @NonNull final InputStream originalBody)
    throws IOException {
    final Document document = Jsoup.parse(new String(IOUtils.readFully(originalBody)));
    document.outputSettings().prettyPrint(true);

    final Elements elements = document.getElementsByTag("head");
    if (elements.size() > 0) {
      elements.get(0).prepend(new String(webViewJavascript(context)));
    }

    return new ByteArrayInputStream(document.toString().getBytes("UTF-8"));
  }

  protected byte[] webViewJavascript(final Context context) throws IOException {
    return IOUtils.readFully(context.getAssets().open("www/WebViewJavascript.html"));
  }

  protected String httpMethod() {
    String httpMethod = "GET";
    if (formContents != null && formContents.method != null) {
      httpMethod = formContents.method.toUpperCase();
    }
    return httpMethod;
  }

  protected boolean isInterceptable(@NonNull final Uri uri) {
    return KSUri.isKickstarterUri(uri, webEndpoint);
  }

  protected WebResourceResponse noopWebResourceResponse() throws IOException {
    return new WebResourceResponse("application/JavaScript", null, new ByteArrayInputStream(new byte[0]));
  }

  private void initializeRequestHandlers() {
    Collections.addAll(requestHandlers,
      new RequestHandler(KSUri::isModalUri, this::startModalWebViewActivity),
      new RequestHandler(KSUri::isProjectUri, this::startProjectActivity)
    );
  }

  private boolean startModalWebViewActivity(@NonNull final Request request, @NonNull final WebView webView) {
    final Activity context = (Activity) webView.getContext();
    final Intent intent = new Intent(context, WebViewActivity.class)
      .putExtra(IntentKey.URL, request.urlString());
    context.startActivity(intent);
    context.overridePendingTransition(R.anim.slide_in_bottom, R.anim.fade_out);

    return true;
  }

  private boolean startProjectActivity(@NonNull final Request request, @NonNull final WebView webView) {
    final Matcher matcher = Pattern.compile("[a-zA-Z0-9_-]+\\z").matcher(Uri.parse(request.urlString()).getPath());
    if (!matcher.find()) {
      return false;
    }
    final Activity activity = (Activity) webView.getContext();
    final Intent intent = new Intent(activity, ProjectActivity.class)
      .putExtra(IntentKey.PROJECT_PARAM, matcher.group())
      .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    activity.startActivity(intent);

    return true;
  }

  private boolean handleRequest(@NonNull final Request request, @NonNull final WebView webView) {
    if (initialPageLoad) {
      // Avoid infinite loop where webView.loadUrl is intercepted, invoking a new activity, which is the same URL
      // and therefore also intercepted.
      return false;
    }

    final Uri uri = Uri.parse(request.urlString());
    for (final RequestHandler requestHandler : requestHandlers) {
      if (requestHandler.matches(uri, webEndpoint) && requestHandler.action(request, webView)) {
        return true;
      }
    }

    return false;
  }

  private void webViewPageIntercepted(final @NonNull String url) {
    if (delegate != null) {
      delegate.webViewPageIntercepted(this, url);
    }

  }

  public class MimeHeaders {
    public String type = null;
    public String encoding = null;

    public MimeHeaders(@NonNull final String contentType) {
      // Extract mime and encoding from string, e.g. "text/html; charset=utf-8"
      final Matcher matcher = Pattern.compile("(\\A[\\w\\/]+); charset=([\\w/-]+)\\z")
        .matcher(contentType);
      if (matcher.matches()) {
        type = matcher.group(1);
        encoding = matcher.group(2).toUpperCase();
      }
    }
  }
}
