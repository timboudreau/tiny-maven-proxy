Tiny Maven Proxy
================

Is exactly what it says it is - a tiny proxy server for Maven, which you can
run on your local network.  The *only* thing it does (at present) is proxy
stuff Maven downloads and cache it.

If you have a slow-ish internet connection, and you have multiple machines
or a team that will all be building and downloading, this is the project for
you.

It is a tiny server you can run with `java -jar` somewhere on your network,
and configure Maven to use.  It is written with [acteur](http://timboudreau.com/blog/updatedActeur/read)
and Netty, meaning that it is asynchronous, with a small memory footprint
and low memory usage.

You give it a list of repository URLs to proxy, and a folder to cache files
in, and run it.  That's it.

Download [a recent build here](http://timboudreau.com/builds/job/tiny-maven-proxy/)

Configuration
-------------

There are two properties you'll want to set.  You can either set them from
the command-line, or in a `tiny-maven-proxy.properties` file that can live in
`/etc`, `/opt/local/etc`, `/~` or `./`.


#### Example

        java -jar tiny-maven-proxy.jar --maven.dir /var/lib/maven --urls https://repo1.maven.org/maven2,http://bits.netbeans.org/maven2/

or you could create `/etc/tiny-maven-proxy.properties` and put in it:

```
maven.dir=/var/lib/maven
urls=urls https://repo1.maven.org/maven2,http://bits.netbeans.org/maven2/
```

#### Defaults

If `maven.dir` is not set, it will create a `/maven` directory in the system
temporary dir (on most OSs this is wiped on reboot).

The following is the list of Maven repositories it proxies by default, if you 
do not set the `urls` setting:

    * https://repo1.maven.org/maven2
    * http://bits.netbeans.org/maven2/
    * http://bits.netbeans.org/nexus/content/repositories/snapshots/
    * https://timboudreau.com/builds/plugin/repository/everything/
    * https://maven.java.net/content/groups/public/
    * https://oss.sonatype.org/


What This Project Is Not
------------------------

It is not a full-featured Maven proxy, such as Nexus or Artifactory.  Those
are great if you need to manage complex mirroring setups, authentication, etc.

It does no authentication, validation, checksum checking (but your Maven client
will, so you'll get the same result as if you'd downloaded things directly).


Logging
-------

The project uses [bunyan-java](https://github.com/timboudreau/bunyan-java) for
logging in JSON format - [more about bunyan-java](http://timboudreau.com/blog/bunyan/read),
making it easy to collect metrics and stats and process log files using the
`bunyan` command-line utility (to get that, install [NodeJS](http://nodejs.org)
and then run `nbm install -g bunyan` on the command-line).


To-Dos
------

 * Clean out `-SNAPSHOT` dependencies periodically
