package com.yourdomain.statusbarscroll; // <-- change to your module package

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class XposedMod implements IXposedHookLoadPackage {

    // Preference key for enabling/disabling module
    private static final String PREFS_NAME = "statusbar_scroll_prefs";
    private static final String PREF_ENABLE_KEY = "enable_scroll_to_top";

    // Candidate SystemUI classes; add more if your ROM uses different names
    private static final String[] CANDIDATE_STATUSBAR_CLASSES = new String[]{
            "com.android.systemui.statusbar.StatusBar",
            "com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.phone.StatusBarWindowView",
            "com.android.systemui.statusbar.StatusBarWindowView",
            "com.android.systemui.statusbar.phone.StatusBar" // extra candidate
    };

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        try {
            if (!"com.android.systemui".equals(lpparam.packageName)) return;

            XposedBridge.log("[StatusBarScroll] hooking SystemUI");
            final ClassLoader cl = lpparam.classLoader;

            for (String candidate : CANDIDATE_STATUSBAR_CLASSES) {
                try {
                    final Class<?> statusBarClass = XposedHelpers.findClassIfExists(candidate, cl);
                    if (statusBarClass == null) {
                        XposedBridge.log("[StatusBarScroll] class not found: " + candidate);
                        continue;
                    }

                    // Hook onAttachedToWindow if available; fallback to onFinishInflate or onCreate
                    String[] lifecycleCandidates = new String[]{"onAttachedToWindow", "onFinishInflate", "onCreate", "onLayout"};
                    boolean hooked = false;
                    for (String lifecycle : lifecycleCandidates) {
                        try {
                            XposedHelpers.findAndHookMethod(statusBarClass, lifecycle, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        // Respect user preference stored in module prefs
                                        boolean enabled = readModuleEnabledFlag();
                                        if (!enabled) {
                                            XposedBridge.log("[StatusBarScroll] disabled in prefs");
                                            return;
                                        }

                                        Object statusBarInstance = param.thisObject;
                                        View statusBarView = extractViewFromInstance(statusBarInstance, cl);
                                        if (statusBarView == null) {
                                            XposedBridge.log("[StatusBarScroll] couldn't get statusBarView");
                                            return;
                                        }

                                        installGestureOnStatusBar(statusBarView, cl);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[StatusBarScroll] afterHookedMethod error: " + t);
                                    }
                                }
                            });
                            XposedBridge.log("[StatusBarScroll] hooked " + candidate + "#" + lifecycle);
                            hooked = true;
                            break;
                        } catch (Throwable t) {
                            // method not found on this class - try next lifecycle method
                            XposedBridge.log("[StatusBarScroll] can't hook " + candidate + " " + lifecycle + " : " + t.getMessage());
                        }
                    }
                    if (hooked) break; // we hooked successfully - no need to try other classes
                } catch (Throwable t) {
                    XposedBridge.log("[StatusBarScroll] error with candidate " + candidate + " : " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] handleLoadPackage error: " + t);
        }
    }

    // Try to extract a View from statusBarInstance:
    private View extractViewFromInstance(Object instance, ClassLoader cl) {
        try {
            if (instance instanceof View) {
                return (View) instance;
            }

            // Common field names that might hold the status bar view
            String[] fieldCandidates = new String[]{"mStatusBarView", "mStatusBarWindow", "mStatusBar", "mView", "mRootView"};
            for (String f : fieldCandidates) {
                try {
                    Object val = XposedHelpers.getObjectField(instance, f);
                    if (val instanceof View) return (View) val;
                } catch (Throwable ignored) { /* try next */ }
            }
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] extractViewFromInstance error: " + t);
        }
        return null;
    }

    private void installGestureOnStatusBar(final View statusBarView, final ClassLoader cl) {
        try {
            final Context ctx = statusBarView.getContext();
            final GestureDetector gesture = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    XposedBridge.log("[StatusBarScroll] double-tap detected");
                    // Post to UI thread quickly to avoid blocking
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            handleScrollToTop(cl);
                        } catch (Throwable t) {
                            XposedBridge.log("[StatusBarScroll] handleScrollToTop failed: " + t);
                        }
                    });
                    return true;
                }
            });

            // Keep existing touch behavior intact by returning false
            statusBarView.setOnTouchListener((v, event) -> {
                try {
                    gesture.onTouchEvent(event);
                } catch (Throwable t) {
                    XposedBridge.log("[StatusBarScroll] gesture error: " + t);
                }
                return false;
            });

            XposedBridge.log("[StatusBarScroll] gesture installed on statusBarView");
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] installGestureOnStatusBar error: " + t);
        }
    }

    // Reads a boolean flag from module prefs in the module package (module's own context)
    // NOTE: when running inside SystemUI, getModuleContext is not available; this reads
    // the prefs file directly via the file-based SharedPreferences path (best-effort).
    private boolean readModuleEnabledFlag() {
        try {
            // Attempt to read prefs via direct file path in /data/data/<module-package>/shared_prefs/
            // Change package name below to your module package if required.
            String modulePackage = "com.yourdomain.statusbarscroll"; // <-- change to your module package
            String prefsFilePath = "/data/data/" + modulePackage + "/shared_prefs/" + PREFS_NAME + ".xml";
            // Use XposedHelpers to read SharedPreferences if possible (some frameworks support it)
            try {
                Context appCtx = (Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", null),
                        "currentApplication");
                if (appCtx != null) {
                    Context moduleCtx = appCtx.createPackageContext(modulePackage, Context.CONTEXT_IGNORE_SECURITY);
                    SharedPreferences prefs = moduleCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    return prefs.getBoolean(PREF_ENABLE_KEY, true);
                }
            } catch (Throwable t) {
                // fallback - try reading via default (true)
                XposedBridge.log("[StatusBarScroll] couldn't read module prefs via context: " + t);
            }
            // default to enabled if we can't read prefs
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] readModuleEnabledFlag error: " + t);
            return true;
        }
    }

    // Main routine: iterate root views and find the first scrollable widget, then scroll it to top
    private void handleScrollToTop(ClassLoader cl) {
        try {
            // Get WindowManagerGlobal instance through reflection
            Class<?> wmgClass = XposedHelpers.findClassIfExists("android.view.WindowManagerGlobal", cl);
            if (wmgClass == null) {
                wmgClass = XposedHelpers.findClassIfExists("android.view.WindowManagerImpl", cl);
            }
            if (wmgClass == null) {
                XposedBridge.log("[StatusBarScroll] WindowManagerGlobal class not found");
                return;
            }

            Object wmgInstance = null;
            try {
                // getInstance()
                Method getInstance = wmgClass.getDeclaredMethod("getInstance");
                getInstance.setAccessible(true);
                wmgInstance = getInstance.invoke(null);
            } catch (NoSuchMethodException nsm) {
                // older versions may use getDefault()
                try {
                    Method getDefault = wmgClass.getDeclaredMethod("getDefault");
                    getDefault.setAccessible(true);
                    wmgInstance = getDefault.invoke(null);
                } catch (Throwable t2) {
                    XposedBridge.log("[StatusBarScroll] couldn't get WindowManagerGlobal instance: " + t2);
                }
            }

            if (wmgInstance == null) {
                XposedBridge.log("[StatusBarScroll] wmgInstance null");
                return;
            }

            // Try to get root views array: method names vary
            Object viewsObj = null;
            String[] viewMethodNames = new String[]{"getRootViews", "getViewRootNames", "getViews", "getRootViewCount"}; // fallback names
            for (String mName : new String[]{"getRootViews", "getViews"}) {
                try {
                    Method m = wmgClass.getDeclaredMethod(mName);
                    m.setAccessible(true);
                    viewsObj = m.invoke(wmgInstance);
                    if (viewsObj != null) break;
                } catch (Throwable ignored) { }
            }
            if (viewsObj == null) {
                // on some ROMs WindowManagerGlobal has getRootViewInfos or returns List; try another approach:
                try {
                    Method m = wmgClass.getMethod("getRootViewCount");
                    m.setAccessible(true);
                    Object count = m.invoke(wmgInstance);
                    if (count instanceof Integer) {
                        int c = (Integer) count;
                        List<View> tmp = new ArrayList<>();
                        for (int i = 0; i < c; i++) {
                            try {
                                Method get = wmgClass.getMethod("getRootView", int.class);
                                Object rv = get.invoke(wmgInstance, i);
                                if (rv instanceof View) tmp.add((View) rv);
                            } catch (Throwable ignored) { }
                        }
                        viewsObj = tmp.toArray(new View[0]);
                    }
                } catch (Throwable ignored) { }
            }

            View[] rootViews = null;
            if (viewsObj instanceof View[]) {
                rootViews = (View[]) viewsObj;
            } else if (viewsObj instanceof List) {
                List l = (List) viewsObj;
                List<View> tmp = new ArrayList<>();
                for (Object o : l) if (o instanceof View) tmp.add((View) o);
                rootViews = tmp.toArray(new View[0]);
            }

            if (rootViews == null) {
                XposedBridge.log("[StatusBarScroll] couldn't obtain root views");
                return;
            }

            for (View root : rootViews) {
                try {
                    View target = findFirstScrollable(root, cl);
                    if (target != null) {
                        XposedBridge.log("[StatusBarScroll] found scrollable: " + target.getClass().getName());
                        performScrollToTop(target, cl);
                        return;
                    }
                } catch (Throwable t) {
                    XposedBridge.log("[StatusBarScroll] error scanning root view: " + t);
                }
            }

            XposedBridge.log("[StatusBarScroll] no scrollable found in root views");
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] handleScrollToTop unexpected error: " + t);
        }
    }

    // Recursively searches for a scrollable child - uses reflection-aware checks for RecyclerView
    private View findFirstScrollable(View root, ClassLoader cl) {
        if (root == null) return null;
        try {
            // Check AbsListView (ListView, GridView)
            if (root instanceof android.widget.AbsListView) return root;
            // Check ScrollView
            if (root instanceof android.widget.ScrollView) return root;
            // Check WebView
            if (root instanceof android.webkit.WebView) return root;

            // RecyclerView may be from android.support or androidx; check via reflection
            Class<?> rvClass = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView", cl);
            if (rvClass == null) rvClass = XposedHelpers.findClassIfExists("android.support.v7.widget.RecyclerView", cl);
            if (rvClass != null) {
                if (rvClass.isInstance(root)) return root;
            }

            // If ViewGroup, recurse
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View found = findFirstScrollable(vg.getChildAt(i), cl);
                    if (found != null) return found;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] findFirstScrollable error: " + t);
        }
        return null;
    }

    // Performs scrolling using reflection-safe calls
    private void performScrollToTop(final View v, ClassLoader cl) {
        try {
            if (v == null) return;

            // If RecyclerView
            Class<?> rvClass = XposedHelpers.findClassIfExists("androidx.recyclerview.widget.RecyclerView", cl);
            if (rvClass == null) rvClass = XposedHelpers.findClassIfExists("android.support.v7.widget.RecyclerView", cl);
            if (rvClass != null && rvClass.isInstance(v)) {
                try {
                    Method smooth = rvClass.getMethod("smoothScrollToPosition", int.class);
                    smooth.invoke(v, 0);
                    return;
                } catch (Throwable t) {
                    // fallback: call scrollToPosition if exists
                    try {
                        Method scroll = rvClass.getMethod("scrollToPosition", int.class);
                        scroll.invoke(v, 0);
                        return;
                    } catch (Throwable t2) {
                        XposedBridge.log("[StatusBarScroll] RecyclerView scroll method failed: " + t2);
                    }
                }
            }

            // AbsListView (ListView / GridView)
            if (v instanceof android.widget.AbsListView) {
                try {
                    Method m = android.widget.AbsListView.class.getMethod("smoothScrollToPositionFromTop", int.class, int.class);
                    m.invoke(v, 0, 0);
                    return;
                } catch (Throwable t) {
                    // fallback to setSelection
                    try {
                        Method setSel = android.widget.AbsListView.class.getMethod("setSelection", int.class);
                        setSel.invoke(v, 0);
                        return;
                    } catch (Throwable t2) {
                        XposedBridge.log("[StatusBarScroll] AbsListView scroll failed: " + t2);
                    }
                }
            }

            // ScrollView
            if (v instanceof android.widget.ScrollView) {
                ((android.widget.ScrollView) v).post(() -> ((android.widget.ScrollView) v).smoothScrollTo(0, 0));
                return;
            }

            // WebView
            if (v instanceof android.webkit.WebView) {
                try {
                    android.webkit.WebView wv = (android.webkit.WebView) v;
                    // best-effort: evaluate JS to scroll to top
                    wv.post(() -> {
                        try {
                            // prefer pageUp if it exists
                            boolean result = wv.pageUp(true);
                            if (!result) {
                                // fallback to JS
                                try {
                                    wv.evaluateJavascript("window.scrollTo(0,0);", null);
                                } catch (Throwable t) {
                                    wv.scrollTo(0, 0);
                                }
                            }
                        } catch (Throwable ignored) { wv.scrollTo(0,0); }
                    });
                    return;
                } catch (Throwable t) {
                    XposedBridge.log("[StatusBarScroll] WebView scroll failed: " + t);
                }
            }

            // Generic fallback: call view.scrollTo(0,0) on UI thread
            v.post(() -> {
                try {
                    v.scrollTo(0, 0);
                } catch (Throwable t) {
                    XposedBridge.log("[StatusBarScroll] fallback scrollTo failed: " + t);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[StatusBarScroll] performScrollToTop unexpected: " + t);
        }
    }
}
	/* Some sort of crappy IPC */
	class ScrollViewReceiver extends BroadcastReceiver {
		private ScrollView mScrollView;
		public ScrollViewReceiver(ScrollView view) {
			mScrollView = view;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (isViewInViewBounds(getContentViewFromContext(mScrollView.getContext()), mScrollView))
				mScrollView.smoothScrollTo(0, 0);
		}
	};

	class AbsListViewReceiver extends BroadcastReceiver {
		private AbsListView mListView;
		public AbsListViewReceiver(AbsListView view) {
			mListView = view;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if (isViewInViewBounds(getContentViewFromContext(mListView.getContext()), mListView))
				mListView.smoothScrollToPosition(0);
		}
	}

	@Override
	public void initZygote(StartupParam startupParam) throws Throwable {
		/* AbsListView, it's one instance of a scroller */
		findAndHookMethod(AbsListView.class, "initAbsListView", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				AbsListView view = (AbsListView) param.thisObject;
				if (!(view.getContext() instanceof Activity))
					return;
				Activity activity = (Activity) view.getContext();		
				AbsListViewReceiver receiver = new AbsListViewReceiver(view);
				addReceiverToActivity(activity, receiver);
				activity.registerReceiver(receiver, new IntentFilter(INTENT_SCROLL_TO_TOP));
			}
		});

		/* Another one */
		findAndHookMethod(ScrollView.class, "initScrollView", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				ScrollView view = (ScrollView) param.thisObject;
				if (!(view.getContext() instanceof Activity))
					return;
				Activity activity = (Activity) view.getContext();		
				ScrollViewReceiver receiver = new ScrollViewReceiver(view);
				addReceiverToActivity(activity, receiver);
				activity.registerReceiver(receiver, new IntentFilter(INTENT_SCROLL_TO_TOP));
			}
		});

		/* FYI, there are some manufacturer specific ones, like Samsung's TouchWiz ones.
		 * I'll look into those later on...
		 */

		/* We need to register and unregister receivers in onPause and onResume 
		 * otherwise, Android bitches about it (for good (memory) reason(s) probably). We do that
		 * by keeping an ArrayList of BroadcastReceivers in all activities. There might be a better
		 * way...
		 */
		Class<?> ActivityClass = findClass("android.app.Activity", null);
		findAndHookMethod(ActivityClass, "onResume", new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				Activity activity = (Activity) param.thisObject;
				resumeBroadcastReceivers(activity);
			}
		});

		findAndHookMethod(ActivityClass, "onPause", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				Activity activity = (Activity) param.thisObject;
				pauseBroadcastReceivers(activity);
			}
		});
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (!lpparam.packageName.equals("com.android.systemui"))
			return;

		Class<?> StatusBarWindowView = findClass("com.android.systemui.statusbar.phone.StatusBarWindowView",
				lpparam.classLoader);

		findAndHookMethod(StatusBarWindowView, "onInterceptTouchEvent", MotionEvent.class, new XC_MethodHook() {
			@Override
			protected void afterHookedMethod(MethodHookParam param) throws Throwable {
				MotionEvent ev = (MotionEvent) param.args[0];
				View view = (View) param.thisObject;
				switch (ev.getAction() & MotionEvent.ACTION_MASK) {
				case MotionEvent.ACTION_DOWN:
					mDownX = ev.getX();
					mDownY = ev.getY();
					mIsClick = true;
					break;
				case MotionEvent.ACTION_CANCEL:
				case MotionEvent.ACTION_UP:
					if (mIsClick) {
						try {
							/* Get NotificationPanelView instance, it subclasses PanelView */
							Object notificationPanelView =
									XposedHelpers.getObjectField(param.thisObject, "mNotificationPanel");

							float expandedFraction = (Float) XposedHelpers.callMethod(notificationPanelView,
									"getExpandedFraction");

							if (expandedFraction < 0.1)
								view.getContext().sendBroadcast(new Intent(INTENT_SCROLL_TO_TOP));
						} catch (Throwable t) {
							XposedBridge.log("StatusBarScrollToTop: Unable to determine expanded fraction: " + t.getMessage());
							t.printStackTrace();
							view.getContext().sendBroadcast(new Intent(INTENT_SCROLL_TO_TOP));
						}
					}
					break;
				case MotionEvent.ACTION_MOVE:
					if (mIsClick && (Math.abs(mDownX - ev.getX()) > SCROLL_THRESHOLD || Math.abs(mDownY - ev.getY()) > SCROLL_THRESHOLD)) {
						mIsClick = false;
					}
					break;
				default:
					break;
				}
			}
		});
	}

	/* Helpers so the code looks less like shit */
	private static void addReceiverToActivity(Activity activity, BroadcastReceiver receiver) {
		ArrayList<BroadcastReceiver> receivers = getReceiversForActivity(activity);
		receivers.add(receiver);
		XposedHelpers.setAdditionalInstanceField(activity, KEY_RECEIVERS, receivers);
	}

	@SuppressWarnings("unchecked")
	private static ArrayList<BroadcastReceiver> getReceiversForActivity(Activity activity) {
		ArrayList<BroadcastReceiver> receivers = null;
		try {
			receivers = (ArrayList<BroadcastReceiver>) XposedHelpers.getAdditionalInstanceField(activity,
					KEY_RECEIVERS);
		} catch (Throwable t) {
			t.printStackTrace();
		}

		if (receivers == null) {
			receivers = new ArrayList<BroadcastReceiver>();
		}

		return receivers;
	}

	private static void resumeBroadcastReceivers(Activity activity) {
		ArrayList<BroadcastReceiver> receivers = getReceiversForActivity(activity);
		for (BroadcastReceiver receiver : receivers) {
			activity.registerReceiver(receiver, new IntentFilter(INTENT_SCROLL_TO_TOP));
		}
	}

	private static void pauseBroadcastReceivers(Activity activity) {
		ArrayList<BroadcastReceiver> receivers = getReceiversForActivity(activity);
		for (BroadcastReceiver receiver : receivers) {
			activity.unregisterReceiver(receiver);
		}
	}

	/* Check if the View is visible to the user, i.e on screen.
	 * We do this since in tabbed interfaces, we can cause a scrollbar that's off screen
	 * to go to the top as well as the visible one.
	 */
	private static boolean isViewInViewBounds(View mainView, View view) {
		/* Failsafe */
		if (mainView == null)
			return true;

		Rect bounds = new Rect();
		mainView.getHitRect(bounds);
		return view.getLocalVisibleRect(bounds);
	}

	/* This works because Context is actually Activity downcasted */
	private static View getContentViewFromContext(Context context) {
		if (!(context instanceof Activity))
			return null;

		return ((Activity) context).findViewById(android.R.id.content);
	}

	/* And that's a wrap */
}
