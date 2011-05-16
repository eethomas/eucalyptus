package com.eucalyptus.webui.client.activity;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.eucalyptus.webui.client.ClientFactory;
import com.eucalyptus.webui.client.place.ConfigPlace;
import com.eucalyptus.webui.client.service.SearchRange;
import com.eucalyptus.webui.client.service.SearchResult;
import com.eucalyptus.webui.client.service.SearchResultCache;
import com.eucalyptus.webui.client.service.SearchResultFieldDesc;
import com.eucalyptus.webui.client.service.SearchResultRow;
import com.eucalyptus.webui.client.session.SessionData;
import com.eucalyptus.webui.client.view.LoadingAnimationView;
import com.eucalyptus.webui.client.view.ConfigView;
import com.google.gwt.activity.shared.AbstractActivity;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.http.client.URL;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.AcceptsOneWidget;

public class ConfigActivity extends AbstractActivity implements ConfigView.Presenter {
  
  public static final String TITLE = "EUCALYPTUS SERVICE COMPONENTS";
  
  private static final Logger LOG = Logger.getLogger( ConfigActivity.class.getName( ) );

  private static final int DEFAULT_PAGE_SIZE = 25;
  private static final int DETAIL_PANE_SIZE = 360;//px
  
  private ClientFactory clientFactory;
  private ConfigPlace place;
  private int pageSize;
  
  private String search;
  private SearchResultCache cache = new SearchResultCache( );
  
  private AcceptsOneWidget container;
  private ConfigView view;
  
  private SearchResultRow currentSelected = null;
  
  public ConfigActivity( ConfigPlace place, ClientFactory clientFactory ) {
    this.place = place;
    this.search = URL.decode( place.getSearch( ) );
    this.clientFactory = clientFactory;
    this.pageSize = this.clientFactory.getSessionData( ).getIntProperty( SessionData.SEARCH_RESULT_PAGE_SIZE, DEFAULT_PAGE_SIZE );    
  }
  
  @Override
  public void start( AcceptsOneWidget container, EventBus eventBus ) {
    this.container = container;
    // Hide detail view at the beginning
    this.clientFactory.getShellView( ).hideDetail( );
    
    this.clientFactory.getShellView( ).getContentView( ).setContentTitle( TITLE );
    // Show loading first
    LoadingAnimationView view = this.clientFactory.getLoadingAnimationView( );
    container.setWidget( view );
    
    doSearch( URL.decode( place.getSearch( ) ), new SearchRange( 0, pageSize ) );
  }
  
  private void showView( SearchResult result ) {
    if ( this.view == null ) {
      this.view = this.clientFactory.getConfigView( );
      this.view.setPresenter( this );
      container.setWidget( this.view );
    }
    this.view.showSearchResult( result );
  }
  
  private void displayData( SearchResult result ) {
    LOG.log( Level.INFO, "Received " + result );
    cache.update( result );
    showView( result );
  }
  
  private void doSearch( String query, SearchRange range ) {
    LOG.log( Level.INFO, "'service' new search: " + query );
    this.clientFactory.getBackendService( ).lookupConfiguration( this.clientFactory.getLocalSession( ).getSession( ), query, range, new AsyncCallback<SearchResult>( ) {

      @Override
      public void onFailure( Throwable cause ) {
        LOG.log( Level.WARNING, "Failed to get configurations: " + cause );
        displayData( null );
      }

      @Override
      public void onSuccess( SearchResult result ) {
        displayData( result );
      }
      
    } );
  }

  @Override
  public void handleRangeChange( SearchRange range ) {
    SearchResult result = cache.lookup( range );
    if ( result != null ) {
      // Use the cached result if search range does not change
      showView( result );
    } else {
      doSearch( this.search, range );
    }
  }

  @Override
  public void onSelectionChange( SearchResultRow selection ) {
    this.currentSelected = selection;
    if ( selection == null ) {
      this.clientFactory.getShellView( ).hideDetail( );
    } else {
      this.clientFactory.getShellView( ).showDetail( DETAIL_PANE_SIZE );
      ArrayList<SearchResultFieldDesc> descs = new ArrayList<SearchResultFieldDesc>( );
      descs.addAll( cache.getDescs( ) );
      descs.addAll( selection.getExtraFieldDescs( ) );
      this.clientFactory.getDetailView( ).showData( descs, selection.getRow( ) );      
    }
  }

  @Override
  public int getPageSize( ) {
    return pageSize;
  }

  @Override
  public void saveValue( ArrayList<String> values ) {
    LOG.log( Level.INFO, "Saving values: " + values );
  }
  
}
