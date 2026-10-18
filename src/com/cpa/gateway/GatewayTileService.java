package com.cpa.gateway;

import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.cpa.gateway.core.Gateway;
import com.cpa.gateway.core.Log;

/**
 * 快捷设置磁贴：一键启停网关。
 *
 * 之前启停网关必须进应用、找到主页那个按钮；磁贴把这一步压到"下拉 → 点一下"。
 * 状态直接读 Gateway.isRunning()，磁贴每次展开/被添加时刷新一次，
 * 点击等价于前台服务通知上的"启动/停止"。
 */
public class GatewayTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        render();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        render();
    }

    @Override
    public void onClick() {
        super.onClick();
        try {
            Gateway.init(this);
            /*
             * 磁贴是个**开关**：点一下就取反用户看到的状态。
             *
             * 这里原来写的是 `running ? STOP : START`，而 running 读的是实时状态——
             * 和 4.30.19 主界面那个 bug 是同一个形状：保活任务若刚好把网关拉起来，
             * 用户想"启动"、按下去实际发的是 STOP，表现成"点了没反应/反而停了"。
             *
             * 现在按状态机的语义来：只要不是"在服务/正在启动"，就是一次启动意图；
             * 已经是运行中就变成停止。STOPPED_BY_USER / STOPPED_IDLE / FAILED
             * 三种"没在跑"都走启动，不再受后台是否抢跑影响。
             */
            boolean wantStart = !Gateway.state().isAlive();
            Intent svc = new Intent(this, GatewayService.class);
            svc.setAction(wantStart ? GatewayService.ACTION_START : GatewayService.ACTION_STOP);
            if (wantStart && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Log.i("磁贴：" + (wantStart ? "启动网关" : "停止网关")
                    + "（当前状态 " + Gateway.state() + "）");
        } catch (Throwable t) {
            Log.w("磁贴操作失败：" + t);
        }
        render();
    }

    private void render() {
        try {
            Tile tile = getQsTile();
            if (tile == null) return;
            Gateway.init(this);
            com.cpa.gateway.core.GatewayState st = Gateway.state();
            tile.setState(st.isAlive() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel("网关" + st.label());
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notify));
            tile.updateTile();
        } catch (Throwable t) {
            Log.w("磁贴刷新失败：" + t);
        }
    }
}
