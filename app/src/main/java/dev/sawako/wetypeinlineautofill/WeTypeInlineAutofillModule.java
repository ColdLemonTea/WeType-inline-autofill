package dev.sawako.wetypeinlineautofill;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.view.inputmethod.InputMethodInfo;
import android.widget.inline.InlinePresentationSpec;

import androidx.autofill.inline.UiVersions;
import androidx.autofill.inline.common.ImageViewStyle;
import androidx.autofill.inline.common.TextViewStyle;
import androidx.autofill.inline.common.ViewStyle;
import androidx.autofill.inline.v1.InlineSuggestionUi;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

public final class WeTypeInlineAutofillModule extends XposedModule {
    private static final String TAG = "WeTypeInlineAutofill";
    private static final String PACKAGE_NAME = "com.tencent.wetype";
    private static final String IME_PROCESS = PACKAGE_NAME + ":hld";
    private static final String IME_CLASS = PACKAGE_NAME + ".plugin.hld.WxHldService";

    private static final String ID_ENABLED = "system_wetype_inline_enabled";
    private static final String ID_BACK_INVOKED = "wetype_back_invoked_enabled";
    private static final String ID_REQUEST = "wetype_inline_request";
    private static final String ID_RESPONSE = "wetype_inline_response";
    private static final String ID_START_INPUT = "wetype_inline_start_input";
    private static final String ID_CANDIDATES = "wetype_inline_candidates";

    private String processName;
    private boolean systemHookInstalled;
    private boolean backInvokedHookInstalled;
    private boolean imeHooksInstalled;
    private boolean candidateHookInstalled;
    private InlineSuggestionsUi inlineUi;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "Loaded in " + processName);
    }

    @Override
    @SuppressLint("BlockedPrivateApi")
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        if (systemHookInstalled) {
            return;
        }
        try {
            Method method = InputMethodInfo.class.getDeclaredMethod(
                    "isInlineSuggestionsEnabled");
            method.setAccessible(true);
            hook(method).setId(ID_ENABLED).intercept(this::interceptInlineEnabled);
            systemHookInstalled = true;
            log(Log.INFO, TAG, "Enabled system_server inline capability hook");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Unable to hook InputMethodInfo", throwable);
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!PACKAGE_NAME.equals(param.getPackageName())) {
            return;
        }
        installBackInvokedHook();
        if (imeHooksInstalled || !IME_PROCESS.equals(processName)) {
            return;
        }
        try {
            Method request = InputMethodService.class.getDeclaredMethod(
                    "onCreateInlineSuggestionsRequest", Bundle.class);
            Method response = InputMethodService.class.getDeclaredMethod(
                    "onInlineSuggestionsResponse", InlineSuggestionsResponse.class);
            Class<?> imeClass = Class.forName(IME_CLASS, false, param.getDefaultClassLoader());
            Method startInput = imeClass.getDeclaredMethod(
                    "onStartInputView", EditorInfo.class, boolean.class);

            hook(request).setId(ID_REQUEST).intercept(this::interceptRequest);
            hook(response).setId(ID_RESPONSE).intercept(this::interceptResponse);
            hook(startInput).setId(ID_START_INPUT).intercept(this::interceptStartInput);
            imeHooksInstalled = true;
            installCandidateHook(param.getDefaultClassLoader());
            log(Log.INFO, TAG, "Installed WeType IME hooks");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Unable to install WeType IME hooks", throwable);
        }
    }

    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        boolean ready = inlineUi == null || inlineUi.prepareForHotReload();
        if (!ready) {
            log(Log.WARN, TAG, "Hot reload deferred because inline UI detach timed out");
        }
        return ready;
    }

    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        processName = param.getProcessName();
        int replaced = 0;
        boolean hadBackInvokedHook = false;
        for (XposedInterface.HookHandle oldHandle : param.getOldHookHandles()) {
            if (ID_BACK_INVOKED.equals(oldHandle.getId())) {
                hadBackInvokedHook = true;
            }
            XposedInterface.Hooker replacement = hookerFor(oldHandle.getId());
            if (replacement == null) {
                oldHandle.unhook();
                continue;
            }
            try {
                oldHandle.replaceHook(replacement);
                if (ID_BACK_INVOKED.equals(oldHandle.getId())) {
                    backInvokedHookInstalled = true;
                } else if (ID_CANDIDATES.equals(oldHandle.getId())) {
                    candidateHookInstalled = true;
                }
                replaced++;
            } catch (Throwable throwable) {
                log(Log.ERROR, TAG, "Unable to replace hook " + oldHandle.getId(), throwable);
            }
        }
        if (!hadBackInvokedHook && (PACKAGE_NAME.equals(processName)
                || (processName != null && processName.startsWith(PACKAGE_NAME + ":")))) {
            installBackInvokedHook();
        }
        log(Log.INFO, TAG, "Hot reload complete in " + processName + ", hooks=" + replaced);
    }

    private XposedInterface.Hooker hookerFor(String id) {
        if (ID_ENABLED.equals(id)) {
            return this::interceptInlineEnabled;
        }
        if (ID_BACK_INVOKED.equals(id)) {
            return this::interceptBackInvokedEnabled;
        }
        if (ID_REQUEST.equals(id)) {
            return this::interceptRequest;
        }
        if (ID_RESPONSE.equals(id)) {
            return this::interceptResponse;
        }
        if (ID_START_INPUT.equals(id)) {
            return this::interceptStartInput;
        }
        if (ID_CANDIDATES.equals(id)) {
            return this::interceptCandidates;
        }
        return null;
    }

    private Object interceptInlineEnabled(XposedInterface.Chain chain) throws Throwable {
        Object object = chain.getThisObject();
        if (object instanceof InputMethodInfo
                && PACKAGE_NAME.equals(((InputMethodInfo) object).getPackageName())) {
            return true;
        }
        return chain.proceed();
    }

    private Object interceptBackInvokedEnabled(XposedInterface.Chain chain) throws Throwable {
        Object object = chain.getThisObject();
        if (object instanceof ApplicationInfo
                && PACKAGE_NAME.equals(((ApplicationInfo) object).packageName)) {
            return true;
        }
        return chain.proceed();
    }

    private Object interceptRequest(XposedInterface.Chain chain) throws Throwable {
        if (!(chain.getThisObject() instanceof InputMethodService)
                || !isWeTypeService(chain.getThisObject())) {
            return chain.proceed();
        }
        return createRequest((InputMethodService) chain.getThisObject());
    }

    private Object interceptResponse(XposedInterface.Chain chain) throws Throwable {
        if (!(chain.getThisObject() instanceof InputMethodService)
                || !isWeTypeService(chain.getThisObject())) {
            return chain.proceed();
        }
        InputMethodService service = (InputMethodService) chain.getThisObject();
        installCandidateHook(service.getClass().getClassLoader());
        InlineSuggestionsResponse response = (InlineSuggestionsResponse) chain.getArg(0);
        return ui().show(service, response) ? true : chain.proceed();
    }

    private Object interceptStartInput(XposedInterface.Chain chain) throws Throwable {
        boolean restarting = (boolean) chain.getArg(1);
        if (chain.getThisObject() instanceof InputMethodService
                && InlineSuggestionsUi.shouldClearOnStartInputView(restarting)) {
            ui().clearForNewInput((InputMethodService) chain.getThisObject());
        }
        return chain.proceed();
    }

    private Object interceptCandidates(XposedInterface.Chain chain) throws Throwable {
        Object candidates = chain.getArg(0);
        boolean newList = (boolean) chain.getArg(2);
        Object result = chain.proceed();
        if (candidates instanceof ArrayList) {
            ui().updateCandidates(newList, !((ArrayList<?>) candidates).isEmpty());
        }
        return result;
    }

    private void installCandidateHook(ClassLoader loader) {
        if (candidateHookInstalled) {
            return;
        }
        try {
            Class<?> candidateClass = Class.forName(
                    InlineSuggestionsUi.CANDIDATE_CLASS, false, loader);
            Method update = InlineSuggestionsUi.findCandidateUpdateMethod(candidateClass);
            hook(update).setId(ID_CANDIDATES).intercept(this::interceptCandidates);
            candidateHookInstalled = true;
            log(Log.INFO, TAG, "Installed WeType candidate-state hook");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Unable to hook WeType candidate updates", throwable);
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private void installBackInvokedHook() {
        if (backInvokedHookInstalled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        try {
            Method method = ApplicationInfo.class.getDeclaredMethod(
                    "isOnBackInvokedCallbackEnabled");
            method.setAccessible(true);
            hook(method).setId(ID_BACK_INVOKED).intercept(this::interceptBackInvokedEnabled);
            backInvokedHookInstalled = true;
            log(Log.INFO, TAG, "Enabled WeType OnBackInvokedCallback opt-in");
        } catch (Throwable throwable) {
            log(Log.ERROR, TAG, "Unable to enable WeType OnBackInvokedCallback opt-in", throwable);
        }
    }

    private synchronized InlineSuggestionsUi ui() {
        if (inlineUi == null) {
            inlineUi = new InlineSuggestionsUi();
        }
        return inlineUi;
    }

    private static boolean isWeTypeService(Object object) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            if (IME_CLASS.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static InlineSuggestionsRequest createRequest(Context context) {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int foreground = dark ? Color.WHITE : Color.rgb(32, 33, 36);
        int secondary = dark ? Color.rgb(190, 190, 190) : Color.rgb(95, 99, 104);
        int horizontalPadding = dp(context, 10);

        Bundle styles = UiVersions.newStylesBuilder()
                .addStyle(InlineSuggestionUi.newStyleBuilder()
                        .setSingleIconChipStyle(new ViewStyle.Builder()
                                .setBackgroundColor(Color.TRANSPARENT)
                                .setPadding(horizontalPadding, 0, horizontalPadding, 0)
                                .build())
                        .setChipStyle(new ViewStyle.Builder()
                                .setBackgroundColor(Color.TRANSPARENT)
                                .setPadding(horizontalPadding, 0, horizontalPadding, 0)
                                .build())
                        .setTitleStyle(new TextViewStyle.Builder()
                                .setTextColor(foreground)
                                .setTextSize(14f)
                                .build())
                        .setSubtitleStyle(new TextViewStyle.Builder()
                                .setTextColor(secondary)
                                .setTextSize(12f)
                                .build())
                        .setStartIconStyle(new ImageViewStyle.Builder()
                                .setTintList(ColorStateList.valueOf(secondary))
                                .build())
                        .setEndIconStyle(new ImageViewStyle.Builder()
                                .setTintList(ColorStateList.valueOf(secondary))
                                .build())
                        .build())
                .build();
        InlinePresentationSpec spec = new InlinePresentationSpec.Builder(
                new Size(0, 0), new Size(Integer.MAX_VALUE, Integer.MAX_VALUE))
                .setStyle(styles)
                .build();
        return new InlineSuggestionsRequest.Builder(Collections.singletonList(spec))
                .setMaxSuggestionCount(5)
                .build();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
