package app.synapse.localllm

import android.app.Application
import app.synapse.localllm.di.SynapseApplicationGraph

class SynapseApplication : Application() {
    lateinit var graph: SynapseApplicationGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = SynapseApplicationGraph.create(this)
    }
}
