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
            boolean running = Gateway.isRunning();
            Intent svc = new Intent(this, GatewayService.class);
            svc.setAction(running ? GatewayService.ACTION_STOP : GatewayService.ACTION_START);
            if (!running && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Log.i("磁贴：" + (running ? "停止网关" : "启动网关"));
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
            boolean running = Gateway.isRunning();
            tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.setLabel(running ? "网关运行中" : "网关已停止");
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notify));
            tile.updateTile();
        } catch (Throwable t) {
            Log.w("磁贴刷新失败：" + t);
        }
    }
}
