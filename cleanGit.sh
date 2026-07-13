#!/bin/bash

REMOTE="origin"

# Track words in the format |type:word|type:word|
seen_words="|"

# WORDS TO IGNORE for branch deduplication
ignore_words=" add added update updated fix fixed remove removed create created implement use move moved clean test better temp force improve improved make "

echo "Syncing with remote server ($REMOTE)..."
git fetch $REMOTE --prune
echo "-----------------------------------"
echo "Analyzing REMOTE branches..."
echo "-----------------------------------"

# Iterate over remote references instead of local ones
git for-each-ref --sort=-committerdate refs/remotes/$REMOTE/ --format='%(refname:short)' | while read -r remote_branch; do

    # 'remote_branch' comes as 'origin/feature/name'. Extract only 'feature/name'
    branch="${remote_branch#$REMOTE/}"

    # Skip remote HEAD (e.g., origin/HEAD)
    if [[ "$branch" == "HEAD" ]]; then continue; fi

    # 1. Verify branch type
    if [[ ! "$branch" =~ ^(bugfix|chore|feature|hotfix|refactor)/ ]]; then
        continue
    fi

    # Extract type and description
    branch_type="${branch%%/*}"
    branch_desc="${branch#*/}"

    is_duplicate=0
    matched_reason=""
    
    # 2. Special rule: chore and hotfix are deleted by default
    if [[ "$branch_type" == "chore" || "$branch_type" == "hotfix" ]]; then
        is_duplicate=1
        matched_reason="Default type ($branch_type)"
    else
        # 3. Common word logic for bugfix, feature, and refactor
        clean_desc=$(echo "$branch_desc" | tr '[:upper:]' '[:lower:]' | sed 's/[-_]/ /g')
        
        for word in $clean_desc; do
            if [ ${#word} -le 2 ]; then continue; fi
            if [[ "$ignore_words" == *" $word "* ]]; then continue; fi

            if [[ "$seen_words" == *"|$branch_type:$word|"* ]]; then
                is_duplicate=1
                matched_reason="Common word: '$word'"
                break 
            fi
        done
    fi

    # 4. Execute action on REMOTE
    if [ $is_duplicate -eq 1 ]; then
        # Actual deletion command on server.
        # COMMENTED OUT for safety. Remove '#' to activate.
        
        # if git push $REMOTE --delete "$branch" >/dev/null 2>&1; then
        #     echo -e "\033[31m[DELETED FROM REMOTE]\033[0m $branch ($matched_reason)"
        # else
        #     echo -e "\033[33m[REMOTE ERROR]\033[0m Could not delete $branch from server."
        # fi
        
        # Line below is for display only (Dry Run).
        # Delete or comment it out if you activate the 'if' block above.
        echo -e "\033[31m[TO BE DELETED FROM REMOTE]\033[0m $branch ($matched_reason)"
    else
        echo -e "\033[32m[KEPT ON REMOTE]\033[0m $branch"
        
        # Save words
        clean_desc=$(echo "$branch_desc" | tr '[:upper:]' '[:lower:]' | sed 's/[-_]/ /g')
        for word in $clean_desc; do
            if [ ${#word} -gt 2 ] && [[ ! "$ignore_words" == *" $word "* ]]; then
                seen_words="${seen_words}${branch_type}:${word}|"
            fi
        done
    fi
done

echo "-----------------------------------"
echo "Remote cleanup finished."