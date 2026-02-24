** AGENT INGORE **


operio-core ConsentSyncService is going to become a PlatformSyncService which is going to be built on top of redis streams
the redis stream will have a dedicated acl with a platform admin user and password

- all writes that occur within any services of the spi should now go through the sync service
- 