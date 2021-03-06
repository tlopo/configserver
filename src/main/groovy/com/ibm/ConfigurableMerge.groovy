package com.ibm

import com.ibm.utils.Deflatter
import com.ibm.utils.Flatter
import com.ibm.utils.Json
import com.ibm.utils.Merger
import com.ibm.utils.Yaml
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.cloud.config.server.resource.NoSuchResourceException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Controller
class ConfigurableMerge {

    @Autowired
    ConfigService configService

    ResponseEntity<String> merge(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable('name') String name,
            @PathVariable('profile') String profile,
            @PathVariable('format') String format,
            @RequestParam(value='branch', required = false) String branch,
            @RequestParam(value='tag', required = false) String tag
            ) throws Exception {

        // Needs request to know its path
        // Based on path it will parse its settings on application.ctx
        // Do the merge accordingly

        branch = branch ?: 'master'

        def ctx = Application.instance.ctx as Map
        def endpointName = request.getRequestURI().split("/")[1]
        List profiles = profile.split(",")
        //List allProfiles = null

        Map endpoint = ctx.endpoints[endpointName] as Map
        List configs = []

        endpoint.merge.each { Map app ->
            String appName
            if (app.application == 'CURRENT') {
                appName = name
            }else {
                appName = app.application
            }
            List allProfiles = (app.profiles + profiles).unique {a,b -> a<=>b }
            String label = tag ?: branch
            configs +=  getConfigs(appName, allProfiles,label)
        }

        Config mergedConfig = getMergedConfig(configs, (format.toUpperCase() as ConfigFormat))
        response
        String str = mergedConfig.content

        //return new ResponseEntity<>(HttpStatus.OK);
        return new ResponseEntity<String>(str, null, HttpStatus.OK)
    }


    private List<Config> getConfigs(name, profiles, label) {
        List<Config> configs = new ArrayList<Config>()
        profiles.each { profile ->
            for (ConfigFormat format : ConfigFormat.values()) {
                Config config = configService.get(name, profile, label, format)
                if (config) {
                    configs.push(config)
                    break
                }
            }
        }
        if (configs.size() == 0) {
            throw new NoSuchResourceException("Unable to get configs for name: ${name} profiles: ${profiles} label: ${label}")
        }
        configs
    }
    private Config getMergedConfig(List<Config> configs, ConfigFormat outputFormat) {
        Map merged = Merger.deepMerge(*(configs.collect{getMapFromConfig(it)}.reverse()))
        String content = null
        switch(outputFormat) {
            case ConfigFormat.JSON:
                content = Json.dump(merged)
                break
            case ConfigFormat.PROPERTIES:
                content = new Flatter().flat(merged)
                break
            case ConfigFormat.YML:
            case ConfigFormat.YAML:
                content = Yaml.dump(merged)
            default:
                break
        }
        return new Config(configs[0].name, "<merged ${configs.collect{it.profile}.join(",")}>", configs[0].label, outputFormat, content)
    }

    private Map getMapFromConfig(Config config) {
        Map map = null
        switch (config.format){
            case ConfigFormat.PROPERTIES:
                Properties p = new Properties()
                p.load(new StringReader(config.content))

                // We have to load the dump because it still behaves as properties
                def obj = Json.load( Json.dump(p as Map))

                String flat = new Flatter().flat(obj)
                map = new Deflatter(flat).deflat()
                break
            case ConfigFormat.JSON:
            case ConfigFormat.YAML:
            case ConfigFormat.YML:
                map = Yaml.load(config.content)
                break
        }
        map
    }
}
