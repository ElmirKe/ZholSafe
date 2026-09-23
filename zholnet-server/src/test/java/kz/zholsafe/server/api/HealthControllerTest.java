package kz.zholsafe.server.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import kz.zholsafe.server.config.ServerBeans;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import(ServerBeans.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get(ApiPaths.HEALTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.contractVersion").value(1));
    }
}
