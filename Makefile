.PHONY: clean jar outdated tag install deploy tree test repl

clean:
	rm -rf target

jar: tag
	clojure -A:jar

outdated:
	clojure -M:outdated

tag:
	clojure -A:tag

install: jar
	clojure -A:install

deploy: jar
	clojure -A:deploy

tree:
	clojure -Xdeps tree

test:
	clojure -X:test :patterns '[".*test.*"]'

repl:
	clojure -M:dev:test:repl
