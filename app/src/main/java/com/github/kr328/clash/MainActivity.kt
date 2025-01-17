package com.github.kr328.clash

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.core.model.TunnelState
import com.github.kr328.clash.design.MainDesign
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import com.hiddify.clash.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity<MainDesign>() {
    override suspend fun main() {
        val design = MainDesign(this)

        setContentDesign(design)

        design.fetch()

        val ticker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart,
                        Event.ServiceRecreated,
                        Event.ClashStop, Event.ClashStart,
                        Event.ProfileLoaded, Event.ProfileChanged -> design.fetch()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        MainDesign.Request.ToggleStatus -> {
                            if (clashRunning)
                                stopClashService()
                            else
                                design.startClash()
                        }
                        MainDesign.Request.UpdateProfile -> {
                            withProfile {
                                var active=queryActive()
                                val uuid=active?.uuid
                                if (uuid!=null) {
                                    update(uuid)
                                }
                            }
                        }
                        MainDesign.Request.OpenIP -> {
                            var browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://ipleak.net/")
                            );
                            startActivity(browserIntent);
                        }
                        MainDesign.Request.SetGlobalMode -> {
                            val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }
                            configuration.mode=TunnelState.Mode.Global
                            withClash { patchOverride(Clash.OverrideSlot.Persist,configuration) }
                            design.setMode(TunnelState.Mode.Global)

                        }
                        MainDesign.Request.SetRuleMode -> {
                            val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }
                            configuration.mode=TunnelState.Mode.Rule
                            withClash { patchOverride(Clash.OverrideSlot.Persist,configuration) }
                            design.setMode(TunnelState.Mode.Rule)

                        }
                        MainDesign.Request.OpenProxy ->
                            startActivity(ProxyActivity::class.intent)
                        MainDesign.Request.OpenProfiles ->
                            startActivity(ProfilesActivity::class.intent)
                        MainDesign.Request.OpenProviders ->
                            startActivity(ProvidersActivity::class.intent)
                        MainDesign.Request.OpenLogs ->
                            startActivity(LogsActivity::class.intent)
                        MainDesign.Request.OpenSettings ->
                            startActivity(SettingsActivity::class.intent)
                        MainDesign.Request.OpenHelp ->{
                            var browserIntent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://t.me/hiddify")
                            );
                            startActivity(browserIntent);
                        }
//                            startActivity(HelpActivity::class.intent)
                        MainDesign.Request.OpenAbout ->
                            design.showAbout(queryAppVersionName())
                        MainDesign.Request.CreateClipboard ->{
                            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.text?.toString()
                                ?.let { it1 -> Utils.addClashProfile(design.context, it1) };
//                            startActivity(NewProfileActivity::class.intent)
                        }
                    }
                }
                if (clashRunning) {
                    ticker.onReceive {
                        design.fetchTraffic()
                    }
                }
            }
        }
    }

    private suspend fun setModeAndCheck(mode:TunnelState.Mode){
        if (mode==TunnelState.Mode.Global){
            withProfile {
                var active=queryActive()
                val uuid=active?.uuid
                if (uuid!=null) {
                    var selected=SelectionDao().querySelections(uuid)
                    if (selected.isEmpty() ||selected[0].selected=="DIRECT"||selected[0].selected=="GLOBAL") {

                        withClash {
                            var groups=queryProxyGroupNames(false).filterNot { it->it=="GLOBAL" }
//                            for(groupname in queryProxyGroupNames()){
//                                queryProxyGroup(groupname).proxies[0]
//                            }
                            if (groups.isNotEmpty())
                                patchSelector("GLOBAL", groups[0])
                            else
                                patchSelector("GLOBAL", "auto")

                        }
                    }
                }
            }

        }
        design?.setMode(mode)
    }
    private suspend fun MainDesign.fetch() {
        setClashRunning(clashRunning)

        val state = withClash {
            queryTunnelState()
        }
        val providers = withClash {
            queryProviders()
        }
        val configuration = withClash { queryOverride(Clash.OverrideSlot.Persist) }
        if (configuration.mode==TunnelState.Mode.Rule ||configuration.mode==TunnelState.Mode.Global)
            setModeAndCheck(configuration.mode!!)
        else
            setModeAndCheck(state.mode)
        setHasProviders(providers.isNotEmpty())

        withProfile {
            setProfileName(queryActive()?.name)
            setProfile(queryActive())
        }
    }

    private suspend fun MainDesign.fetchTraffic() {
        withClash {
            setForwarded(queryTrafficTotal())
        }
    }

    private suspend fun MainDesign.startClash() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            showToast(R.string.no_profile_selected, ToastDuration.Long) {
                setAction(R.string.profiles) {
                    startActivity(ProfilesActivity::class.intent)
                }
            }

            return
        }

        val vpnRequest = startClashService()

        try {
            if (vpnRequest != null) {
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK)
                    startClashService()
            }
        } catch (e: Exception) {
            design?.showToast(R.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private suspend fun queryAppVersionName(): String {
        return withContext(Dispatchers.IO) {
            packageManager.getPackageInfo(packageName, 0).versionName
        }
    }
}