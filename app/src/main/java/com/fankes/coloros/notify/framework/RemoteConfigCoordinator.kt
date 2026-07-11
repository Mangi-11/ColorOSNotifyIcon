package com.fankes.coloros.notify.framework

import io.github.libxposed.service.XposedService

/** Owns the app-side mutate-then-publish protocol. Local desired state is never rolled back. */
object RemoteConfigCoordinator {

    fun update(
        service: XposedService,
        mutation: () -> Unit,
        onResult: ((RemoteRuleMirror.PublishResult) -> Unit)? = null,
    ): Boolean {
        try {
            mutation()
        } catch (exception: Exception) {
            RemoteRuleMirror.deliverResultOnMain(
                RemoteRuleMirror.localMutationFailed(exception),
                onResult,
            )
            return false
        }
        RemoteRuleMirror.syncAsync(service, onResult)
        return true
    }

    fun publish(
        service: XposedService,
        onResult: ((RemoteRuleMirror.PublishResult) -> Unit)? = null,
    ) {
        RemoteRuleMirror.syncAsync(service, onResult)
    }
}
