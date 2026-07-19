// IShizukuService.aidl
package rikka.shizuku;

interface IShizukuService {
    int getVersion();
    int getUid();
    int checkPermission();
    int requestPermission(int code);
    IBinder getBinder();
    void addBinderReceivedListener(IBinder listener);
    void removeBinderReceivedListener(IBinder listener);
    void addBinderDeadListener(IBinder listener);
    void removeBinderDeadListener(IBinder listener);
    void addPermissionChangedListener(IBinder listener);
    void removePermissionChangedListener(IBinder listener);
    void addServiceConnection(IBinder connection);
    void removeServiceConnection(IBinder connection);
    int startUserService(in UserServiceArgs args);
    int stopUserService(in UserServiceArgs args);
    int bindUserService(in UserServiceArgs args, IBinder connection);
    int unbindUserService(in UserServiceArgs args, IBinder connection);
    int peekUserService(in UserServiceArgs args);
}