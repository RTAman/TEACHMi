package com.teachmi.backend.home;

import com.teachmi.backend.auth.AuthenticatedMember;
import com.teachmi.backend.home.HomeViews.HomeView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HomeController {
    private final HomeService home;

    HomeController(HomeService home) {
        this.home = home;
    }

    @GetMapping("/api/v1/home")
    ResponseEntity<HomeView> get(HttpServletRequest http) {
        var member = AuthenticatedMember.require(http).userId().toString();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(home.get(member));
    }
}