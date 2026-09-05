package com.lttstore.duper;

import com.lttstore.duper.modules.Auto5b5tDupe;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class DuperAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("5b5t");

    @Override
    public void onInitialize() {
        LOG.info("Initializing or4acle's Funny 5b5t Auto Duper");

        // Modules
        Modules.get().add(new Auto5b5tDupe());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.lttstore.duper";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("mmvanheusden", "meteor-5b5t-addon");
    }
}
