// 逆向自支付宝 SDK 的 IAlixPay 接口（msp service）。
// 方法声明顺序 == transaction code，必须与支付宝 app service 端完全一致，错位则调到别的方法。
// 我们只用 Pay(1) / registerCallback(3) / unregisterCallback(4)，其余方法仅占位以保证 code 对齐。
package com.alipay.android.app;

import com.alipay.android.app.IRemoteServiceCallback;

interface IAlixPay {
    String Pay(String strConfig);                                              // 1
    void test();                                                               // 2
    void registerCallback(IRemoteServiceCallback cb);                          // 3
    void unregisterCallback(IRemoteServiceCallback cb);                        // 4
    String prePay(String strConfig);                                           // 5
    void deployFastConnect();                                                  // 6
    String manager(String strConfig);                                          // 7
    int getVersion();                                                          // 8
    String pay02(String strConfig, in Map data);                              // 9
    String r03(String a, String b, in Map data);                              // 10
    void registerCallback03(IRemoteServiceCallback cb, String a, in Map data); // 11
}
