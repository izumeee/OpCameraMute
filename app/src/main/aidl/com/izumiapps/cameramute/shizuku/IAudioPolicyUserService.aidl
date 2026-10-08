package com.izumiapps.cameramute.shizuku;

/**
 * Shizuku UserServiceの最小公開面。
 * usageは公開せずFOR_SYSTEMだけ、書き込み値は実装側で0/11だけに制限する。
 */
interface IAudioPolicyUserService {
    /** ShizukuがUserServiceを停止するために予約しているtransaction。 */
    void destroy() = 16777114;

    int getForSystem() = 1;

    void setForSystem(int config) = 2;
}
