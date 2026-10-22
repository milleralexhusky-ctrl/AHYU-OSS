package com.cpa.gateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import com.cpa.gateway.core.Log;
import com.cpa.gateway.core.Updater;

/**
 * 收 {@code PackageInstaller} 的安装结果（4.52.0）。
 *
 * <p>存在的理由只有一个：<b>装成功之后把下载包删掉</b>。
 * 不做这件事的话，那个 1MB 的文件会一直留在私有目录里 —— 新版本装完它就没用了，
 * 而用户永远不会再去点它，也没人负责清理。
 *
 * <p>系统弹的那个"是否安装"确认框由 {@code PackageInstaller} 自己管，
 * 用户在框里取消、或者安装失败，都只会走到这里报状态，不做任何补救：
 * 保留下载包是有意的（下次直接点通知就能重试，不必重新下载）。
 *
 * <p>广播接收器不是 Activity，拿不到界面，所以这里只写日志、不发通知 ——
 * 装上之后应用本身会被系统重启，状态通知自然由新的进程重建。
 */
public class InstallResultReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !Updater.ACTION_INSTALL_RESULT.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_SUCCESS:
                Log.i("安装结果：成功，已清理下载包");
                Updater.cleanup(c);
                break;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                /* 用户在系统确认框里点了"取消" —— 最常见的一个分支。
                   保留包，通知还在，随时可以再点一次。 */
                Log.i("安装结果：用户取消（安装包已保留，可重新点通知安装）");
                break;
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                Log.w("安装结果：被系统拦截（" + detail + "）：需要允许本应用「安装未知应用」");
                break;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                Log.e("安装结果：安装包无效（" + detail + "）—— 删除，下次检查会重新下载");
                Updater.cleanup(c);
                break;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                Log.e("安装结果：与已安装应用冲突（" + detail + "）");
                break;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                Log.e("安装结果：存储空间不足（" + detail + "）");
                break;
            default:
                Log.w("安装结果：状态 " + status + "（" + detail + "，安装包已保留）");
                break;
        }
    }
}
