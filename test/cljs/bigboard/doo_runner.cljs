(ns bigboard.doo-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [bigboard.core-test]))

(doo-tests 'bigboard.core-test)

