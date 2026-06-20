// 逆向自支付宝 SDK。方法顺序 == transaction code。Pay() 阻塞期间，支付宝通过它回调我们
// （主要是 startActivity 拉起支付宝授权 UI）。顺序必须与服务端一致。
package com.alipay.android.app;

interface IRemoteServiceCallback {
    void startActivity(String packageName, String className, int flag, in Bundle data); // 1
    void payEnd(boolean isOk, String result);                                            // 2
    boolean isHideLoadingScreen();                                                        // 3
    int getVersion();                                                                     // 4
    void r03(String a, String b, in Map data);                                            // 5
}
