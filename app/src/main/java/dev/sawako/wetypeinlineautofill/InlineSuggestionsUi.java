package dev.sawako.wetypeinlineautofill;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.inline.InlineContentView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class InlineSuggestionsUi {
    static final String CANDIDATE_CLASS =
            "com.tencent.wetype.plugin.hld.candidate.ImeCandidateView";
    private static final String TAG = "WeTypeInlineAutofill";
    private static final int HEIGHT_DP = 40;

    private State state;
    private boolean candidatesActive;

    boolean show(InputMethodService service, InlineSuggestionsResponse response) {
        List<InlineSuggestion> suggestions = response.getInlineSuggestions();
        if (suggestions.isEmpty()) {
            clear(service);
            return true;
        }

        State current = stateFor(service);
        if (current == null) {
            return false;
        }
        clearContent(current);
        if (current.showing) {
            current.restoreShell();
        }

        boolean[] pinned = new boolean[suggestions.size()];
        for (int i = 0; i < suggestions.size(); i++) {
            pinned[i] = suggestions.get(i).getInfo().isPinned();
        }
        int[] order = orderSuggestions(pinned);
        int generation = current.generation;
        for (int i = 1; i < order.length; i++) {
            FrameLayout slot = new FrameLayout(service);
            current.scrollContent.addView(slot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
            inflate(service, current, generation, suggestions.get(order[i]), slot, true);
        }
        if (order[0] >= 0) {
            inflate(service, current, generation, suggestions.get(order[0]),
                    current.pinnedContent, false);
        }
        return true;
    }

    void clear(InputMethodService service) {
        if (state == null || state.service != service) {
            return;
        }
        clearContent(state);
        if (state.showing) {
            state.restoreShell();
        }
    }

    void clearForNewInput(InputMethodService service) {
        candidatesActive = false;
        clear(service);
    }

    void updateCandidates(boolean newList, boolean hasCandidates) {
        boolean active = candidateActiveAfterUpdate(candidatesActive, newList, hasCandidates);
        if (active == candidatesActive) {
            return;
        }
        candidatesActive = active;
        if (state == null) {
            return;
        }
        if (active) {
            if (state.showing) {
                state.restoreShell();
            }
        } else {
            state.showReadyContent();
        }
    }

    boolean prepareForHotReload() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            detach();
            return true;
        }
        CountDownLatch done = new CountDownLatch(1);
        if (!new Handler(Looper.getMainLooper()).post(() -> {
            try {
                detach();
            } finally {
                done.countDown();
            }
        })) {
            return false;
        }
        try {
            return done.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private State stateFor(InputMethodService service) {
        if (state != null && state.service == service && state.root.isAttachedToWindow()
                && state.candidate.isAttachedToWindow()
                && (!state.nativeShell || state.pinnedContent.isAttachedToWindow())) {
            return state;
        }
        detach();
        Window window = service.getWindow().getWindow();
        if (window == null) {
            return null;
        }
        View candidate = findCandidate(window.getDecorView());
        if (candidate == null) {
            return null;
        }

        View candidateContainer = getter(candidate, "getCandidateContainerRl");
        View defaultContainer = getter(candidate, "getDefaultContainerRl");
        View logo = getter(candidate, "getLogoIv");
        View rightContainer = getter(candidate, "getRightContainerLl");
        View normalContainer = rightContainer == null ? null
                : rightContainer.getParent() instanceof View
                ? (View) rightContainer.getParent() : null;
        View candidateContent = normalContainer instanceof ViewGroup
                ? sibling((ViewGroup) normalContainer, rightContainer) : null;
        View alternateContainer = candidateContainer instanceof ViewGroup
                ? sibling((ViewGroup) candidateContainer, normalContainer) : null;
        boolean nativeShell = candidate instanceof ViewGroup
                && candidateContainer instanceof ViewGroup
                && normalContainer instanceof RelativeLayout
                && candidateContent != null
                && candidateContent.getLayoutParams() instanceof RelativeLayout.LayoutParams
                && alternateContainer != null
                && defaultContainer != null
                && rightContainer instanceof ViewGroup;
        int contentHeight = dp(service, HEIGHT_DP);
        int shellHeight = nativeShell ? defaultContainer.getHeight() : 0;
        if (shellHeight <= 0 && nativeShell && defaultContainer.getLayoutParams() != null) {
            shellHeight = defaultContainer.getLayoutParams().height;
        }
        if (shellHeight <= 0) {
            shellHeight = contentHeight;
        }
        int contentOffset = nativeShell ? centerOffset(defaultContainer, logo) : 0;
        int pinnedWidth = nativeShell ? rightContainer.getWidth() : 0;
        if (pinnedWidth <= 0 && nativeShell && rightContainer.getLayoutParams() != null) {
            pinnedWidth = rightContainer.getLayoutParams().width;
        }
        if (pinnedWidth <= 0) {
            pinnedWidth = dp(service, 48);
        }
        FrameLayout root = new FrameLayout(service);
        root.setVisibility(View.GONE);
        FrameLayout pinnedContent = new FrameLayout(service);
        pinnedContent.setVisibility(View.GONE);
        FrameLayout scrollRegion;

        if (nativeShell) {
            RelativeLayout.LayoutParams rootParams = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, shellHeight);
            rootParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            ((ViewGroup) candidate).addView(root, rootParams);

            LinearLayout row = new LinearLayout(service);
            row.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            scrollRegion = new FrameLayout(service);
            row.addView(scrollRegion, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(pinnedContent, new LinearLayout.LayoutParams(
                    pinnedWidth, ViewGroup.LayoutParams.MATCH_PARENT));
            copyBackground(service, defaultContainer, scrollRegion);
            copyBackground(service, defaultContainer, pinnedContent);
        } else {
            if (!(candidate.getParent() instanceof LinearLayout)) {
                return null;
            }
            LinearLayout parent = (LinearLayout) candidate.getParent();
            int index = parent.indexOfChild(candidate);
            copyBackground(service, candidate, root);
            parent.addView(root, index,
                    new LinearLayout.LayoutParams(candidate.getLayoutParams()));

            LinearLayout row = new LinearLayout(service);
            row.setOrientation(LinearLayout.HORIZONTAL);
            root.addView(row, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(service, HEIGHT_DP)));

            scrollRegion = new FrameLayout(service);
            row.addView(scrollRegion, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(pinnedContent, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        HorizontalScrollView scroll = new HorizontalScrollView(service);
        scroll.setFillViewport(true);
        scroll.setHorizontalScrollBarEnabled(false);
        scrollRegion.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout scrollContent = new LinearLayout(service);
        scrollContent.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(scrollContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        SurfaceView scrollSurface = new SurfaceView(service);
        scrollSurface.setZOrderOnTop(true);
        scrollSurface.getHolder().setFormat(PixelFormat.TRANSPARENT);
        scrollSurface.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                cropScrollSurface(scrollSurface);
            }
        });
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, contentHeight);
        surfaceParams.gravity = Gravity.CENTER_VERTICAL;
        surfaceParams.topMargin = contentOffset;
        scrollRegion.addView(scrollSurface, surfaceParams);

        state = new State(service, candidate, root, scroll, scrollSurface, scrollContent,
                scrollRegion, pinnedContent, nativeShell, contentHeight, pinnedWidth,
                contentOffset);
        return state;
    }

    private void inflate(InputMethodService service, State target, int generation,
                         InlineSuggestion suggestion, FrameLayout container,
                         boolean scrollable) {
        try {
            suggestion.inflate(service,
                    new Size(target.contentWidth(scrollable), target.contentHeight),
                    service.getMainExecutor(), view -> {
                        if (view == null) {
                            Log.w(TAG, "Inline suggestion renderer returned no view");
                            return;
                        }
                        if (state != target || target.generation != generation) {
                            return;
                        }
                        if (scrollable) {
                            attachToScrollSurface(target, generation, view);
                            target.scrollViews.add(view);
                        }
                        ViewGroup.LayoutParams source = view.getLayoutParams();
                        FrameLayout.LayoutParams params = source == null
                                ? new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT)
                                : new FrameLayout.LayoutParams(source);
                        if (!scrollable) {
                            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                        }
                        params.height = target.contentHeight;
                        params.gravity = scrollable ? Gravity.CENTER_VERTICAL : Gravity.CENTER;
                        params.topMargin = target.contentOffset;
                        container.addView(view, params);
                        if (!candidatesActive) {
                            target.showReadyContent();
                        }
                    });
        } catch (Throwable throwable) {
            Log.e(TAG, "Unable to inflate inline suggestion", throwable);
        }
    }

    private void attachToScrollSurface(State target, int generation, InlineContentView view) {
        view.setSurfaceControlCallback(new InlineContentView.SurfaceControlCallback() {
            @Override
            public void onCreated(SurfaceControl surfaceControl) {
                if (state != target || target.generation != generation) {
                    return;
                }
                SurfaceControl parent = target.scrollSurface.getSurfaceControl();
                if (parent != null && parent.isValid()) {
                    try (SurfaceControl.Transaction transaction =
                                 new SurfaceControl.Transaction()) {
                        setSurfaceCrop(transaction, target.scrollSurface, parent);
                        transaction.reparent(surfaceControl, parent).apply();
                    }
                }
            }

            @Override
            public void onDestroyed(SurfaceControl surfaceControl) {
            }
        });
    }

    private static void cropScrollSurface(SurfaceView view) {
        SurfaceControl surface = view.getSurfaceControl();
        if (surface == null || !surface.isValid()) {
            return;
        }
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            setSurfaceCrop(transaction, view, surface);
            transaction.apply();
        }
    }

    @SuppressWarnings("deprecation")
    private static void setSurfaceCrop(SurfaceControl.Transaction transaction,
                                       SurfaceView view, SurfaceControl surface) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Rect crop = new Rect(0, 0, width, height);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            transaction.setCrop(surface, crop);
            return;
        }
        int[] location = new int[2];
        view.getLocationInWindow(location);
        transaction.setGeometry(surface, crop, new Rect(location[0], location[1],
                location[0] + width, location[1] + height), Surface.ROTATION_0);
    }

    private void clearContent(State target) {
        target.generation++;
        if (!target.scrollViews.isEmpty()) {
            try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
                for (InlineContentView view : target.scrollViews) {
                    SurfaceControl surface = view.getSurfaceControl();
                    if (surface != null && surface.isValid()) {
                        transaction.reparent(surface, null);
                    }
                }
                transaction.apply();
            }
        }
        target.scrollViews.clear();
        target.scroll.scrollTo(0, 0);
        target.scrollContent.removeAllViews();
        target.pinnedContent.removeAllViews();
    }

    private void detach() {
        if (state == null) {
            return;
        }
        clearContent(state);
        if (state.showing) {
            state.restoreShell();
        }
        if (state.root.getParent() instanceof ViewGroup) {
            ((ViewGroup) state.root.getParent()).removeView(state.root);
        }
        if (state.nativeShell && state.pinnedContent.getParent() instanceof ViewGroup) {
            ((ViewGroup) state.pinnedContent.getParent()).removeView(state.pinnedContent);
        }
        state = null;
    }

    private static View getter(View target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View sibling(ViewGroup parent, View excluded) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != excluded) {
                return child;
            }
        }
        return null;
    }

    private static View findCandidate(View view) {
        if (CANDIDATE_CLASS.equals(view.getClass().getName())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View candidate = findCandidate(group.getChildAt(i));
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static void copyBackground(Context context, View source, View destination) {
        Drawable background = null;
        for (View view = source; view != null && background == null; ) {
            background = view.getBackground();
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        Drawable.ConstantState constantState = background == null ? null
                : background.getConstantState();
        if (constantState != null) {
            destination.setBackground(constantState.newDrawable(context.getResources()).mutate());
            return;
        }
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        destination.setBackgroundColor(dark ? Color.rgb(32, 32, 32) : Color.WHITE);
    }

    private static int centerOffset(View shell, View target) {
        if (shell == null || target == null || shell.getHeight() <= 0
                || target.getHeight() <= 0) {
            return 0;
        }
        int[] shellLocation = new int[2];
        int[] targetLocation = new int[2];
        shell.getLocationInWindow(shellLocation);
        target.getLocationInWindow(targetLocation);
        int offset = targetLocation[1] + target.getHeight() / 2
                - shellLocation[1] - shell.getHeight() / 2;
        return Math.abs(offset) < shell.getHeight() / 4 ? offset : 0;
    }

    static int[] orderSuggestions(boolean[] pinned) {
        int[] result = new int[pinned.length + 1];
        result[0] = -1;
        int next = 1;
        for (int i = 0; i < pinned.length; i++) {
            if (!pinned[i]) {
                continue;
            }
            if (result[0] < 0) {
                result[0] = i;
            } else {
                result[next++] = i;
            }
        }
        for (int i = 0; i < pinned.length; i++) {
            if (!pinned[i]) {
                result[next++] = i;
            }
        }
        return Arrays.copyOf(result, next);
    }

    static boolean shouldClearOnStartInputView(boolean restarting) {
        return !restarting;
    }

    static boolean hasReadyContent(boolean scrollable, boolean pinned) {
        return scrollable || pinned;
    }

    static boolean candidateActiveAfterUpdate(boolean active, boolean newList,
                                              boolean hasCandidates) {
        return hasCandidates || (active && !newList);
    }

    static Method findCandidateUpdateMethod(Class<?> candidateClass)
            throws NoSuchMethodException {
        Method result = null;
        for (Method method : candidateClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() != void.class || parameters.length != 4
                    || parameters[0] != ArrayList.class || parameters[1] != int.class
                    || parameters[2] != boolean.class || parameters[3] != boolean.class) {
                continue;
            }
            if (result != null) {
                throw new NoSuchMethodException("Ambiguous candidate update method in "
                        + candidateClass.getName());
            }
            result = method;
        }
        if (result == null) {
            throw new NoSuchMethodException("Candidate update method not found in "
                    + candidateClass.getName());
        }
        return result;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class State {
        final InputMethodService service;
        final View candidate;
        final FrameLayout root;
        final HorizontalScrollView scroll;
        final SurfaceView scrollSurface;
        final LinearLayout scrollContent;
        final FrameLayout scrollRegion;
        final FrameLayout pinnedContent;
        final List<InlineContentView> scrollViews = new ArrayList<>();
        final boolean nativeShell;
        final int contentHeight;
        final int pinnedWidth;
        final int contentOffset;
        int originalCandidateVisibility;
        int generation;
        boolean showing;

        State(InputMethodService service, View candidate, FrameLayout root,
              HorizontalScrollView scroll, SurfaceView scrollSurface,
              LinearLayout scrollContent, FrameLayout scrollRegion,
              FrameLayout pinnedContent, boolean nativeShell, int contentHeight,
              int pinnedWidth, int contentOffset) {
            this.service = service;
            this.candidate = candidate;
            this.root = root;
            this.scroll = scroll;
            this.scrollSurface = scrollSurface;
            this.scrollContent = scrollContent;
            this.scrollRegion = scrollRegion;
            this.pinnedContent = pinnedContent;
            this.nativeShell = nativeShell;
            this.contentHeight = contentHeight;
            this.pinnedWidth = pinnedWidth;
            this.contentOffset = contentOffset;
        }

        void captureVisibility() {
            originalCandidateVisibility = candidate.getVisibility();
        }

        void showShell(boolean showScrollable, boolean showPinned) {
            candidate.setVisibility(nativeShell ? View.VISIBLE : View.GONE);
            root.setVisibility(View.VISIBLE);
            scrollRegion.setVisibility(showScrollable ? View.VISIBLE : View.INVISIBLE);
            pinnedContent.setVisibility(showPinned ? View.VISIBLE : View.GONE);
            showing = true;
        }

        void showReadyContent() {
            boolean showScrollable = !scrollViews.isEmpty();
            boolean showPinned = pinnedContent.getChildCount() > 0;
            if (!hasReadyContent(showScrollable, showPinned)) {
                return;
            }
            if (!showing) {
                captureVisibility();
            }
            showShell(showScrollable, showPinned);
        }

        void restoreShell() {
            root.setVisibility(View.GONE);
            pinnedContent.setVisibility(View.GONE);
            candidate.setVisibility(originalCandidateVisibility);
            showing = false;
        }

        int contentWidth(boolean scrollable) {
            if (scrollable || !nativeShell) {
                return ViewGroup.LayoutParams.WRAP_CONTENT;
            }
            return pinnedWidth;
        }
    }
}
