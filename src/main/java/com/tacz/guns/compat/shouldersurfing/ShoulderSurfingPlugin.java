package com.tacz.guns.compat.shouldersurfing;

import com.github.exopandora.shouldersurfing.api.plugin.IShoulderSurfingPlugin;
import com.github.exopandora.shouldersurfing.api.client.event.ComputePlayerAimStateEvent;
import com.github.exopandora.shouldersurfing.api.event.IEventBus;
import com.tacz.guns.api.item.IGun;

public class ShoulderSurfingPlugin implements IShoulderSurfingPlugin {
	@Override
	public void register(IEventBus eventBus) {
		eventBus.register(ShoulderSurfingPlugin::computeAimState);
	}

	private static void computeAimState(ComputePlayerAimStateEvent event) {
		// The old adaptive callback matched either hand, independently of TACZ ADS.
		if (event.getEntity().getMainHandItem().getItem() instanceof IGun
				|| event.getEntity().getOffhandItem().getItem() instanceof IGun) {
			event.setResult(true);
		}
	}
}
