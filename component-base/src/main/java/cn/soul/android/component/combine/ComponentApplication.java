package cn.soul.android.component.combine;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;

/**
 * @author panxinghai
 * <p>
 * date : 2019-10-12 11:22
 */
@SuppressLint("NewApi")
abstract public class ComponentApplication extends Application
        implements InitTask {
    private Application mHost;
    private boolean mIsRunAsApplication = false;

    public void setHostApplication(Application application) {
        mHost = application;
    }

    public void setRunAsApplication(boolean runAsApplication) {
        mIsRunAsApplication = runAsApplication;
    }

    public abstract void initAsApplication();

    public abstract void initAsComponent(Application realApplication);

    @Override
    public void onConfigure() {

    }

    @Override
    public void onDependency() {

    }

    @Override
    public void dependsOn(Object... dep) {

    }

    @Override
    public Object[] getDependencies() {
        return new Object[0];
    }

    @Override
    public InitTask[] getDependencyTasks() {
        return new InitTask[0];
    }

    @Override
    public void onExecute() {
        if (mIsRunAsApplication) {
            initAsApplication();
        } else {
            initAsComponent(mHost);
        }
    }

    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    @Override
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        if (mHost != null) {
            mHost.registerActivityLifecycleCallbacks(callback);
        }
    }

    @Override
    public void registerComponentCallbacks(ComponentCallbacks callback) {
        if (mHost != null) {
            mHost.registerComponentCallbacks(callback);
        }
    }

    @Override
    public void unregisterComponentCallbacks(ComponentCallbacks callback) {
        if (mHost != null) {
            mHost.unregisterComponentCallbacks(callback);
        }
    }

    @Override
    public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
        if (mHost != null) {
            mHost.unregisterActivityLifecycleCallbacks(callback);
        }
    }

    @Override
    public void registerOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        if (mHost != null) {
            mHost.registerOnProvideAssistDataListener(callback);
        }
    }

    @Override
    public void unregisterOnProvideAssistDataListener(OnProvideAssistDataListener callback) {
        if (mHost != null) {
            mHost.unregisterOnProvideAssistDataListener(callback);
        }
    }
}